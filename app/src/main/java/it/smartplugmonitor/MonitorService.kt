package it.smartplugmonitor
import android.app.*
import android.content.*
import android.graphics.Color
import android.media.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import java.util.Locale

class MonitorService : Service() {
    companion object {
        private const val CHANNEL_STATUS_ID = "monitor_status_v8"
        private const val CHANNEL_ALERT_ID = "monitor_alert_v8"
        @Volatile var isServiceRunning = false
        @Volatile var lastPowerText = "-- W"
        @Volatile var lastStatusText = "In attesa di avvio"
        @Volatile var lastConnectionText = "Non ancora connesso"
    }
    @Volatile private var running = false
    private var workerThread: Thread? = null
    private var client: TuyaClient? = null
    @Volatile private var alertAttiva = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var forzatoFineCiclo = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_ALARM_ACTION") {
            cancelFineCicloNotification()
            alertAttiva = false
            forzatoFineCiclo = false // L'utente ha premuto OK: sblocca lo stato e torna in attesa
            lastStatusText = "In attesa"
            updateStatusNotification()
            return START_STICKY
        }
        startForeground(1, buildStatusNotification("Avvio in corso..."))
        if (workerThread?.isAlive == true) return START_STICKY
        
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartPlugMonitor::WakeLock").apply { acquire(15 * 60 * 1000L) }
        
        running = true; isServiceRunning = true
        workerThread = Thread { runMonitorLoop() }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; isServiceRunning = false
        workerThread?.interrupt(); workerThread = null
        client?.close(); client = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun runMonitorLoop() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val ip = prefs.getString("ip_address", "") ?: ""
        val deviceId = prefs.getString("device_id", "") ?: ""
        val localKey = prefs.getString("local_key", "") ?: ""
        if (ip.isEmpty() || deviceId.isEmpty() || localKey.isEmpty()) {
            lastStatusText = "Non configurato"; updateStatusNotification(); stopSelf(); return
        }
        val offThreshold = (prefs.getString("off_threshold", "10") ?: "10").toDoubleOrNull() ?: 10.0
        val debounceSeconds = (prefs.getString("debounce_seconds", "60") ?: "60").toLongOrNull() ?: 60L
        
        val basePollMs = 10000L
        client = TuyaClient(deviceId, ip, localKey)
        var stato = "IN_ATTESA"; var inizioSottoSoglia: Long? = null

        while (running) {
            var sleepTime = basePollMs
            try {
                val potenza = client!!.getPower()
                lastPowerText = String.format(Locale.US, "%.1f W", potenza)
                lastConnectionText = "Presa collegata"
                
                if (potenza > offThreshold) {
                    stato = "IN_FUNZIONE"
                    inizioSottoSoglia = null
                    forzatoFineCiclo = false // Se riparte, resetta il blocco visivo
                    lastStatusText = "In funzione"
                    if (alertAttiva) { cancelFineCicloNotification(); alertAttiva = false }
                } else {
                    if (stato == "IN_FUNZIONE") {
                        sleepTime = 3000L 
                        val t0 = inizioSottoSoglia
                        if (t0 == null) {
                            inizioSottoSoglia = System.currentTimeMillis()
                            lastStatusText = "In funzione"
                        } else if (System.currentTimeMillis() - t0 >= debounceSeconds * 1000L) {
                            sendFineCicloNotification(prefs)
                            alertAttiva = true; stato = "IN_ATTESA"; inizioSottoSoglia = null
                            forzatoFineCiclo = true // Attiva il blocco visivo di fine ciclo
                            lastStatusText = "Fine ciclo"
                        } else {
                            lastStatusText = "In funzione"
                        }
                    } else {
                        // Se l'allarme è attivo e non è stato premuto OK, mantiene la scritta "Fine ciclo"
                        lastStatusText = if (forzatoFineCiclo) "Fine ciclo" else "In attesa"
                        inizioSottoSoglia = null
                    }
                }
                updateStatusNotification()
                Thread.sleep(sleepTime)
            } catch (_: InterruptedException) { break } catch (e: Exception) {
                client?.close()
                try { Thread.sleep(5000L) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun sendFineCicloNotification(prefs: SharedPreferences) {
        val title = prefs.getString("alert_title", "Ciclo terminato") ?: "Ciclo terminato"
        val message = prefs.getString("alert_message", "Il dispositivo ha terminato.") ?: "Il dispositivo ha terminato."
        val stopIntent = Intent(this, MonitorService::class.java).apply { action = "STOP_ALARM_ACTION" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        val soundUri = Uri.parse("android.resource://$packageName/raw/alarm_beep")

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(message)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(soundUri)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "OK", stopPendingIntent)
            .build()
            
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2, notification)
    }

    private fun cancelFineCicloNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2)
    }
    private fun buildStatusNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_STATUS_ID)
        .setSmallIcon(android.R.drawable.ic_menu_preferences).setContentTitle("Smart Plug Monitor").setContentText(text)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true).build()
    private fun updateStatusNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildStatusNotification("$lastStatusText — $lastPowerText"))
    }
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_STATUS_ID, "Stato", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        
        val soundUri = Uri.parse("android.resource://$packageName/raw/alarm_beep")
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERT_ID, "Fine ciclo", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(soundUri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setBypassDnd(true)
            enableLights(false)
            enableVibration(false)
        })
    }
}
