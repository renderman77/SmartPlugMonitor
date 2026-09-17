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
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Locale

class MonitorService : Service() {

    companion object {
        private const val CHANNEL_STATUS_ID = "monitor_status_v2"
        private const val CHANNEL_ALERT_ID = "monitor_alert_v2"

        private const val NOTIFICATION_ID_STATUS = 1
        private const val NOTIFICATION_ID_ALERT = 2

        private const val RETRY_DELAY_MS = 2000L
        private const val FAILURES_BEFORE_SHOWING_ERROR = 3

        @Volatile var isServiceRunning: Boolean = false
            private set

        @Volatile var lastPowerText: String = "-- W"
            private set

        @Volatile var lastStatusText: String = "In attesa di avvio"
            private set

        @Volatile var lastConnectionText: String = "Non ancora connesso"
            private set
    }

    @Volatile private var running = false
    private var workerThread: Thread? = null
    private var client: TuyaClient? = null

    /** True se è stata mostrata una notifica di fine ciclo ancora attiva. */
    @Volatile private var alertAttiva = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID_STATUS, buildStatusNotification("Avvio in corso..."))

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

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_STATUS)
        // Non cancelliamo l'alert di fine ciclo: l'utente può chiuderlo dopo

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
            (prefs.getString("debounce_seconds", "60") ?: "60").toLongOrNull() ?: 60L

        // Intervallo di lettura: minimo 2 secondi per ridurre il lag percepito
        val pollIntervalMs =
            ((prefs.getString("poll_interval", "3") ?: "3")
                .toLongOrNull()
                ?.coerceAtLeast(2) ?: 3L) * 1000L

        client = TuyaClient(deviceId, ip, localKey)

        // IN_ATTESA / IN_FUNZIONE
        var stato = "IN_ATTESA"
        var inizioSottoSoglia: Long? = null
        var failureCount = 0

        while (running) {
            try {
                // Best-effort: chiedi aggiornamento DP energetici
                try {
                    client!!.requestDpsRefresh()
                } catch (_: Exception) {
                }

                val potenza = client!!.getPower()
                failureCount = 0
                lastPowerText = String.format(Locale.US, "%.1f W", potenza)
                lastConnectionText = "Presa collegata"

                if (potenza > offThreshold) {
                    // Consumo sopra soglia = ciclo in corso
                    if (stato != "IN_FUNZIONE") {
                        stato = "IN_FUNZIONE"
                    }
                    inizioSottoSoglia = null
                    lastStatusText = "In funzione"

                    // Se c'era una notifica di fine ciclo e riparte un carico,
                    // l'operatore è davanti: chiudi la notifica e continua.
                    if (alertAttiva) {
                        cancelFineCicloNotification()
                        alertAttiva = false
                    }
                } else {
                    // Sotto soglia
                    if (stato == "IN_FUNZIONE") {
                        val t0 = inizioSottoSoglia
                        if (t0 == null) {
                            inizioSottoSoglia = System.currentTimeMillis()
                            // Nessun testo intermedio: resta "In funzione"
                            lastStatusText = "In funzione"
                        } else if (System.currentTimeMillis() - t0 >= debounceSeconds * 1000L) {
                            sendFineCicloNotification(prefs)
                            alertAttiva = true
                            stato = "IN_ATTESA"
                            inizioSottoSoglia = null
                            lastStatusText = "Fine ciclo"
                        } else {
                            lastStatusText = "In funzione"
                        }
                    } else {
                        lastStatusText = "In attesa"
                        inizioSottoSoglia = null
                    }
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
                    lastStatusText = "Presa non raggiungibile"
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
        val title = prefs.getString("alert_title", "Ciclo terminato") ?: "Ciclo terminato"
        val message =
            prefs.getString("alert_message", "Il dispositivo collegato ha terminato.")
                ?: "Il dispositivo collegato ha terminato."

        val openApp = Intent(this, MainActivity::class.java)
        val contentPending =
            PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_IMMUTABLE)

        // Suono di allarme di sistema (funziona anche senza file raw)
        val alarmUri: Uri =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val vibration = longArrayOf(0, 500, 200, 500, 200, 500, 700, 500)

        val notification =
            NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(contentPending)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(alarmUri)
                .setVibrate(vibration)
                .setLights(Color.RED, 500, 500)
                .setAutoCancel(true) // tap = chiude (e interrompe suono su molti dispositivi)
                .setOnlyAlertOnce(false)
                .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_ALERT, notification)
    }

    private fun cancelFineCicloNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_ALERT)
    }

    private fun buildStatusNotification(text: String): Notification {
        val openApp = Intent(this, MainActivity::class.java)
        val pending =
            PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_STATUS_ID)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentTitle("Smart Plug Monitor")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateStatusNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID_STATUS,
            buildStatusNotification("$lastStatusText — $lastPowerText")
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ID canali nuovi (v2): così non restano le impostazioni dei canali vecchi
        val status =
            NotificationChannel(
                CHANNEL_STATUS_ID,
                "Stato monitoraggio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifica permanente mentre il monitoraggio è attivo"
                setSound(null, null)
                enableVibration(false)
            }

        val alarmUri =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val audioAttrs =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        val alert =
            NotificationChannel(
                CHANNEL_ALERT_ID,
                "Fine ciclo",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avviso quando il consumo scende sotto soglia"
                setSound(alarmUri, audioAttrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500, 700, 500)
                enableLights(true)
                lightColor = Color.RED
                setBypassDnd(true)
            }

        manager.createNotificationChannel(status)
        manager.createNotificationChannel(alert)
    }
}
