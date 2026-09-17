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

class MonitorService : Service() {

    companion object {

        private const val CHANNEL_STATUS_ID = "monitor_status"
        private const val CHANNEL_ALERT_ALLARME_ID = "monitor_alert_allarme"
        private const val CHANNEL_ALERT_NORMALE_ID = "monitor_alert_normale"
        private const val CHANNEL_ALERT_VIBRAZIONE_ID = "monitor_alert_vibrazione"

        private const val NOTIFICATION_ID_STATUS = 1
        private const val NOTIFICATION_ID_ALERT = 2

        private const val RETRY_DELAY_MS = 2000L
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

        // Rimuove la notifica di stato quando il monitoraggio si ferma
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_STATUS)

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
            (prefs.getString("off_threshold", "10") ?: "10").toDoubleOrNull() ?: 10.0

        val debounceSeconds =
            (prefs.getString("debounce_seconds", "90") ?: "90").toLongOrNull() ?: 90L

        val pollIntervalMs =
            ((prefs.getString("poll_interval", "5") ?: "5")
                .toLongOrNull()
                ?.coerceAtLeast(1) ?: 5L) * 1000L

        client = TuyaClient(deviceId, ip, localKey)

        var stato = "IN_ATTESA"
        var inizioPausa: Long? = null
        var failureCount = 0

        val FINESTRA_VELOCE_MS = 120_000L
        val INTERVALLO_VELOCE_MS = 4_000L
        val INTERVALLO_STANDBY_MS = maxOf(pollIntervalMs, 15_000L)

        var finestraVelaceFino = System.currentTimeMillis() + FINESTRA_VELOCE_MS

        while (running) {
            try {
                val inFinestraVeloce = System.currentTimeMillis() < finestraVelaceFino

                if (inFinestraVeloce) {
                    client!!.requestDpsRefresh()
                }

                val potenza = client!!.getPower()

                failureCount = 0
                lastPowerText = String.format(Locale.US, "%.1f W", potenza)
                lastConnectionText = "Presa collegata"

                // Soglia unica
                when {
                    potenza > offThreshold -> {
                        stato = "IN_FUNZIONE"
                        inizioPausa = null
                        lastStatusText = "In funzione"
                        finestraVelaceFino = 0L
                    }

                    else -> {
                        if (stato == "IN_FUNZIONE") {
                            val inizio = inizioPausa
                            if (inizio == null) {
                                inizioPausa = System.currentTimeMillis()
                                lastStatusText = "Possibile fine ciclo..."
                            } else if (
                                System.currentTimeMillis() - inizio >= debounceSeconds * 1000L
                            ) {
                                sendFineCicloNotification(prefs)
                                stato = "IN_ATTESA"
                                inizioPausa = null
                                lastStatusText = "Fine ciclo"
                                finestraVelaceFino =
                                    System.currentTimeMillis() + FINESTRA_VELOCE_MS
                            }
                        } else {
                            lastStatusText = "In attesa"
                            inizioPausa = null
                        }
                    }
                }

                updateStatusNotification()

                val prossimoIntervallo =
                    if (System.currentTimeMillis() < finestraVelaceFino) {
                        INTERVALLO_VELOCE_MS
                    } else {
                        INTERVALLO_STANDBY_MS
                    }

                Thread.sleep(prossimoIntervallo)

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

    private fun sendFineCicloNotification(prefs: android.content.SharedPreferences) {
        val style = prefs.getString("notification_style", "allarme") ?: "allarme"

        val title = prefs.getString("alert_title", "Ciclo terminato") ?: "Ciclo terminato"
        val message =
            prefs.getString(
                "alert_message",
                "Il dispositivo collegato ha terminato."
            ) ?: "Il dispositivo collegato ha terminato."

        val channelId =
            when (style) {
                "normale" -> CHANNEL_ALERT_NORMALE_ID
                "vibrazione" -> CHANNEL_ALERT_VIBRAZIONE_ID
                else -> CHANNEL_ALERT_ALLARME_ID
            }

        val notification =
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val statusChannel =
            NotificationChannel(
                CHANNEL_STATUS_ID,
                "Stato monitoraggio",
                NotificationManager.IMPORTANCE_LOW
            )
        manager.createNotificationChannel(statusChannel)

        val alarmVibrationPattern =
            longArrayOf(0, 250, 150, 250, 700, 250, 150, 250)

        val audioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        val alarmSoundUri =
            Uri.parse("android.resource://$packageName/${R.raw.alarm_beep}")

        val allarmeChannel =
            NotificationChannel(
                CHANNEL_ALERT_ALLARME_ID,
                "Fine ciclo — Allarme",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(alarmSoundUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = alarmVibrationPattern
                enableLights(true)
                lightColor = Color.RED
                description = "Suono personalizzato, vibrazione e LED a fine ciclo"
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
                vibrationPattern = alarmVibrationPattern
                description = "Solo vibrazione, senza suono, a fine ciclo"
            }

        manager.createNotificationChannel(allarmeChannel)
        manager.createNotificationChannel(normaleChannel)
        manager.createNotificationChannel(vibrazioneChannel)
    }
}
