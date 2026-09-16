package it.smartplugmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Servizio in primo piano ("foreground service") che tiene viva la
 * connessione alla presa anche quando l'app non è aperta o lo schermo
 * è spento -- cosa che il vecchio thread agganciato a MainActivity non
 * poteva fare, ed è la causa più probabile dei crash precedenti.
 *
 * La logica di rilevamento è la stessa già validata separatamente in
 * Python: due soglie (accensione/spegnimento) più un tempo minimo
 * ("debounce") sotto soglia prima di considerare il ciclo davvero
 * finito, per ignorare le micro-pause dell'elettrodomestico.
 */
class MonitorService : Service() {

    companion object {

        private const val CHANNEL_STATUS_ID = "monitor_status"
        private const val CHANNEL_ALERT_ID = "monitor_alert"

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
        // fretta, Android può terminare il servizio con un crash --
        // molto probabilmente quello che succedeva a schermo spento.
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

        // IN_ATTESA   = in attesa che inizi un ciclo
        // IN_FUNZIONE = elettrodomestico attivo, in attesa della fine
        var stato = "IN_ATTESA"
        var inizioPausa: Long? = null

        while (running) {

            try {

                val potenza = client!!.getPower()

                lastPowerText =
                    String.format(Locale.US, "%.1f W", potenza)

                lastConnectionText = "Presa collegata"

                when {

                    potenza > ON_THRESHOLD_WATT -> {
                        stato = "IN_FUNZIONE"
                        inizioPausa = null
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
                                sendFineCicloNotification()
                                stato = "IN_ATTESA"
                                inizioPausa = null
                            }
                        }
                    }

                    // else: zona intermedia tra le due soglie.
                    // Non tocchiamo inizioPausa: non è né sicuramente
                    // "spenta" né sicuramente "in funzione".
                }

                lastStatusText =
                    if (stato == "IN_FUNZIONE") "In funzione" else "In attesa"

                updateStatusNotification()

                Thread.sleep(pollIntervalMs)

            } catch (_: InterruptedException) {

                break

            } catch (e: Exception) {

                client?.close()

                lastConnectionText = e.message ?: "Errore di connessione"
                lastStatusText = "Presa non raggiungibile"

                updateStatusNotification()

                try {
                    Thread.sleep(minOf(pollIntervalMs, RETRY_DELAY_MS))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun sendFineCicloNotification() {

        val notification =
            NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Bucato pronto")
                .setContentText("La lavatrice ha terminato il ciclo.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
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

            val alertChannel =
                NotificationChannel(
                    CHANNEL_ALERT_ID,
                    "Fine ciclo",
                    NotificationManager.IMPORTANCE_HIGH
                )

            manager.createNotificationChannel(statusChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }
}
