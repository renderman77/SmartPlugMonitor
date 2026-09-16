package it.smartplugmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Servizio in primo piano ("foreground service") che tiene viva la
 * connessione alla presa anche quando l'app non è aperta o lo schermo
 * è spento.
 *
 * Logica di rilevamento: due soglie (accensione/spegnimento) più un
 * tempo minimo ("debounce") sotto soglia prima di considerare il
 * ciclo davvero finito, per ignorare le micro-pause.
 */
class MonitorService : Service() {

    companion object {

        private const val CHANNEL_STATUS_ID = "monitor_status"

        private const val CHANNEL_ALERT_ALLARME_ID = "monitor_alert_allarme"
        private const val CHANNEL_ALERT_NORMALE_ID = "monitor_alert_normale"
        private const val CHANNEL_ALERT_VIBRAZIONE_ID = "monitor_alert_vibrazione"

        private const val NOTIFICATION_ID_STATUS = 1
        private const val NOTIFICATION_ID_ALERT = 2

        /**
         * Soglia di "in funzione". Non è (ancora) esposta nelle
         * Impostazioni: è lo stesso valore già validato nei test
         * precedenti (50W separa bene standby/pausa da funzionamento
         * reale per un piccolo elettrodomestico).
         */
        private const val ON_THRESHOLD_WATT = 50.0

        /** Attesa breve dopo un errore, per riprendere il monitoraggio
         *  più in fretta di un normale ciclo di polling. */
        private const val RETRY_DELAY_MS = 2000L

        /** Quante letture fallite consecutive servono prima di
         *  mostrare davvero "presa non raggiungibile", per non far
         *  lampeggiare l'avviso per un singolo blip temporaneo di
         *  rete che si risolve da solo. */
        private const val FAILURES_BEFORE_SHOWING_ERROR = 3

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var lastPowerText: String = "-- W"
            private set

        @Volatile
        var lastStatusText: String = "In attesa di avvio"
            private set

        @Volatile
        var lastConnectionText: String = "Non ancora connesso"
            private set
    }

    @Volatile
    private var running = false

    private var workerThread: Thread? = null
    private var client: TuyaClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // Va chiamato SUBITO all'avvio del servizio: se non lo fai in
        // fretta, Android può terminare il servizio con un crash.
        startForeground(
            NOTIFICATION_ID_STATUS,
            buildStatusNotification("Avvio in corso...")
        )

        if (workerThread?.isAlive == true) {
            return START_STICKY
        }

        running = true
        isServiceRunning = true

        workerThread = Thread { runMonitorLoop() }.also { it.start() }

        return START_STICKY
    }

    override fun onDestroy() {

        running = false
        isServiceRunning = false

        workerThread?.interrupt()
        workerThread = null

        client?.close()
        client = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runMonitorLoop() {

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val ip = prefs.getString("ip_address", "") ?: ""
        val deviceId = prefs.getString("device_id", "") ?: ""
        val localKey = prefs.getString("local_key", "") ?: ""

        if (ip.isEmpty() || deviceId.isEmpty() || localKey.isEmpty()) {
            lastStatusText = "Non configurato"
            lastConnectionText = "Configura la presa nelle impostazioni"
            updateStatusNotification()
            stopSelf()
            return
        }

        val offThreshold =
            (prefs.getString("off_threshold", "10") ?: "10")
                .toDoubleOrNull() ?: 10.0

        val debounceSeconds =
            (prefs.getString("debounce_seconds", "90") ?: "90")
                .toLongOrNull() ?: 90L

        val pollIntervalMs =
            (
                (prefs.getString("poll_interval", "5") ?: "5")
                    .toLongOrNull()
                    ?.coerceAtLeast(1)
                    ?: 5L
                ) * 1000L

        client = TuyaClient(deviceId, ip, localKey)

        // IN_ATTESA   = in attesa che inizi un ciclo (o appena finito uno)
        // IN_FUNZIONE = elettrodomestico attivo, in attesa della fine
        var stato = "IN_ATTESA"
        var inizioPausa: Long? = null
        var failureCount = 0

        while (running) {

            try {

                val potenza = client!!.getPower()

                failureCount = 0

                lastPowerText =
                    String.format(Locale.US, "%.1f W", potenza)

                lastConnectionText = "Presa collegata"

                when {

                    potenza > ON_THRESHOLD_WATT -> {
                        stato = "IN_FUNZIONE"
                        inizioPausa = null
                        lastStatusText = "In funzione"
                    }

                    potenza < offThreshold -> {

                        if (stato == "IN_FUNZIONE") {

                            val inizio = inizioPausa

                            if (inizio == null) {
                                inizioPausa = System.currentTimeMillis()
                            } else if (
                                System.currentTimeMillis() - inizio
                                >= debounceSeconds * 1000L
                            ) {
                                sendFineCicloNotification(prefs)
                                stato = "IN_ATTESA"
                                inizioPausa = null
                                // Resta scritto "Fine ciclo" (non torna a
                                // "In attesa") finché non riparte un nuovo
                                // ciclo vero, così si vede a colpo d'occhio
                                // che è stato notificato.
                                lastStatusText = "Fine ciclo"
                            }
                        }
                    }

                    // else: zona intermedia tra le due soglie.
                    // Non tocchiamo inizioPausa né lo stato mostrato.
                }

                updateStatusNotification()

                Thread.sleep(pollIntervalMs)

            } catch (_: InterruptedException) {

                break

            } catch (e: Exception) {

                client?.close()

                failureCount++

                if (failureCount >= FAILURES_BEFORE_SHOWING_ERROR) {
                    lastConnectionText = e.message ?: "Errore di connessione"
                    updateStatusNotification()
                }

                try {
                    Thread.sleep(minOf(pollIntervalMs, RETRY_DELAY_MS))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun sendFineCicloNotification(
        prefs: android.content.SharedPreferences
    ) {

        val style = prefs.getString("notification_style", "allarme") ?: "allarme"

        val channelId =
            when (style) {
                "normale" -> CHANNEL_ALERT_NORMALE_ID
                "vibrazione" -> CHANNEL_ALERT_VIBRAZIONE_ID
                else -> CHANNEL_ALERT_ALLARME_ID
            }

        val notification =
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Bucato pronto")
                .setContentText("La lavatrice ha terminato il ciclo.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(NOTIFICATION_ID_ALERT, notification)
    }

    private fun buildStatusNotification(text: String): Notification {

        val openAppIntent = Intent(this, MainActivity::class.java)

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(this, CHANNEL_STATUS_ID)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentTitle("Smart Plug Monitor")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateStatusNotification() {

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val text = "$lastStatusText — $lastPowerText"

        manager.notify(NOTIFICATION_ID_STATUS, buildStatusNotification(text))
    }

    private fun createNotificationChannels() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val statusChannel =
                NotificationChannel(
                    CHANNEL_STATUS_ID,
                    "Stato monitoraggio",
                    NotificationManager.IMPORTANCE_LOW
                )

            manager.createNotificationChannel(statusChannel)

            // Pattern vibrazione: beep-beep-pausa-beep-beep, in millisecondi
            // (il primo valore è un ritardo iniziale, poi vibra/pausa alternati)
            val alarmVibrationPattern =
                longArrayOf(0, 250, 150, 250, 700, 250, 150, 250)

            val audioAttributes =
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

            val alarmSoundUri =
                Uri.parse(
                    "android.resource://$packageName/${R.raw.alarm_beep}"
                )

            val allarmeChannel =
                NotificationChannel(
                    CHANNEL_ALERT_ALLARME_ID,
                    "Fine ciclo — Allarme",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(alarmSoundUri, audioAttributes)
                    enableVibration(true)
                    vibrationPattern = vibrationPattern
                    enableLights(true)
                    lightColor = Color.RED
                    description = "Suono ripetuto, vibrazione e LED a fine ciclo"
                }

            val normaleChannel =
                NotificationChannel(
                    CHANNEL_ALERT_NORMALE_ID,
                    "Fine ciclo — Normale",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableVibration(true)
                    enableLights(true)
                    lightColor = Color.BLUE
                    description = "Suono di notifica standard a fine ciclo"
                }

            val vibrazioneChannel =
                NotificationChannel(
                    CHANNEL_ALERT_VIBRAZIONE_ID,
                    "Fine ciclo — Solo vibrazione",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = vibrationPattern
                    description = "Solo vibrazione, senza suono, a fine ciclo"
                }

            manager.createNotificationChannel(allarmeChannel)
            manager.createNotificationChannel(normaleChannel)
            manager.createNotificationChannel(vibrazioneChannel)
        }
    }
}
