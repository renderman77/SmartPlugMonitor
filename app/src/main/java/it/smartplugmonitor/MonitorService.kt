package it.smartplugmonitor

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import java.util.Locale

class MonitorService : Service() {

    companion object {
        private const val CHANNEL_STATUS_ID = "monitor_status_v10"
        private const val NOTIFICATION_ID_STATUS = 1

        @Volatile var isServiceRunning = false
        @Volatile var lastPowerText = "-- W"
        @Volatile var lastStatusText = "Stopped"
        @Volatile var lastConnectionText = "Not connected"
    }

    @Volatile private var running = false
    private var workerThread: Thread? = null
    private var client: TuyaClient? = null
    @Volatile private var cycleFinishedLocked = false
    private var alarmPlayer: MediaPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        startForeground(NOTIFICATION_ID_STATUS, buildStatusNotification("Starting..."))

        if (workerThread?.isAlive == true) {
            return START_STICKY
        }

        // Impedisce ad Android di addormentare la scheda Wi-Fi a schermo spento
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SmartPlugMonitor::WifiLock").apply {
                acquire()
            }
        } catch (_: Exception) {
        }

        running = true
        isServiceRunning = true
        cycleFinishedLocked = false
        lastStatusText = "Waiting"
        lastPowerText = "-- W"

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

        stopAlarmSound()

        // Rilascia la scheda Wi-Fi tornando ai consumi normali del telefono
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        }
        wifiLock = null

        lastPowerText = "-- W"
        lastStatusText = "Stopped"
        lastConnectionText = "Not connected"

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_STATUS)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runMonitorLoop() {

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val ip = prefs.getString("ip_address", "") ?: ""
        val localKey = prefs.getString("local_key", "") ?: ""

        if (ip.isEmpty() || localKey.isEmpty()) {
            lastStatusText = "Not configured"
            lastConnectionText = "Set up the plug in Settings"
            updateStatusNotification()
            stopSelf()
            return
        }

        val offThreshold =
            (prefs.getString("off_threshold", "10") ?: "10").toDoubleOrNull() ?: 10.0

        val debounceSeconds =
            (prefs.getString("debounce_seconds", "60") ?: "60").toLongOrNull() ?: 60L

        client = TuyaClient(ip, localKey)

        var state = "WAITING"
        var belowThresholdSince: Long? = null

        while (running) {
            // Mantiene i 4 secondi stabili per non far addormentare la cache della presa smart,
            // velocizzando il controllo a 1 secondo solo durante il conteggio finale (debounce)
            val sleepTime = if (state == "RUNNING" && belowThresholdSince != null) 1000L else 4000L

            try {

                val power = client!!.getPower()

                lastPowerText = String.format(Locale.US, "%.1f W", power)
                lastConnectionText = "Plug connected"

                if (power > offThreshold) {

                    state = "RUNNING"
                    belowThresholdSince = null
                    cycleFinishedLocked = false
                    lastStatusText = "Running"
                    stopAlarmSound()

                } else {

                    if (state == "RUNNING") {

                        val since = belowThresholdSince

                        if (since == null) {
                            belowThresholdSince = System.currentTimeMillis()
                            lastStatusText = "Running"
                        } else if (
                            System.currentTimeMillis() - since >= debounceSeconds * 1000L
                        ) {
                            state = "WAITING"
                            belowThresholdSince = null
                            cycleFinishedLocked = true
                            lastStatusText = "Cycle finished"
                            startAlarmSound()
                        } else {
                            lastStatusText = "Running"
                        }

                    } else {
                        lastStatusText = if (cycleFinishedLocked) "Cycle finished" else "Waiting"
                        belowThresholdSince = null
                    }
                }

                updateStatusNotification()
                Thread.sleep(sleepTime)

            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                client?.close()
                try {
                    Thread.sleep(4000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun startAlarmSound() {

        if (alarmPlayer != null) return

        try {

            val uri = Uri.parse("android.resource://$packageName/raw/alarm_beep")

            alarmPlayer =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@MonitorService, uri)
                    isLooping = true
                    prepare()
                    start()
                }

        } catch (_: Exception) {
            alarmPlayer = null
        }
    }

    private fun stopAlarmSound() {

        try {
            alarmPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }

        alarmPlayer = null
    }

    private fun buildStatusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Smart Plug Monitor")
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

    private fun updateStatusNotification() {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID_STATUS,
            buildStatusNotification("$lastStatusText — $lastPowerText")
        )
    }

    private fun createNotificationChannels() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS_ID,
                "Monitoring status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}
