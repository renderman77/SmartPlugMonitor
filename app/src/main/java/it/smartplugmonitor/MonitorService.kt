package it.smartplugmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.Locale

class MonitorService : Service() {

    companion object {
        private const val CHANNEL_STATUS_ID = "monitor_status_v11"
        private const val NOTIFICATION_ID_STATUS = 1

        /** Poll fisso: compromesso stabilità presa / reattività */
        private const val POLL_MS = 7_000L
        private const val ERROR_BACKOFF_MS = 10_000L

        @Volatile var isServiceRunning = false
        @Volatile var lastPowerText = "-- W"
        @Volatile var lastStatusText = "Stopped"
        @Volatile var lastConnectionText = "Not connected"
    }

    @Volatile private var running = false
    private var workerThread: Thread? = null
    private var client: TuyaClient? = null
    @Volatile private var cycleFinishedLocked = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID_STATUS, buildStatusNotification("Starting..."))

        if (workerThread?.isAlive == true) {
            return START_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmartPlugMonitor::WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        running = true
        isServiceRunning = true
        cycleFinishedLocked = false
        lastStatusText = "Waiting"
        lastPowerText = "-- W"
        lastConnectionText = "Connecting..."

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

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null

        lastPowerText = "-- W"
        lastStatusText = "Stopped"
        lastConnectionText = "Not connected"

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_STATUS)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runMonitorLoop() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val ip = prefs.getString("ip_address", "")?.trim().orEmpty()
        val localKey = prefs.getString("local_key", "")?.trim().orEmpty()

        if (ip.isEmpty() || localKey.isEmpty()) {
            lastStatusText = "Not configured"
            lastConnectionText = "Set IP and Local Key in Settings"
            updateStatusNotification()
            stopSelf()
            return
        }

        val offThreshold =
            (prefs.getString("off_threshold", "10") ?: "10").toDoubleOrNull() ?: 10.0
        val debounceSeconds =
            (prefs.getString("debounce_seconds", "60") ?: "60").toLongOrNull() ?: 60L

        client = TuyaClient(ip, localKey)

        // ==========================================
        // UNICA AGGIUNTA RISPETTO AL TUO ORIGINALE:
        // Una singola lettura attiva immediata SOLO all'avvio per azzerare il ritardo iniziale.
        // Se fallisce per qualsiasi motivo viene ignorata e non blocca l'applicazione.
        try {
            client!!.getPower()
        } catch (_: Exception) {}
        // ==========================================

        var state = "WAITING"
        var belowThresholdSince: Long? = null

        while (running) {
            try {
                // LETTURA PASSIVA STANDARD (Ripristinata al 100% come piace alla tua presa)
                val power = client!!.getPowerPassive()

                lastPowerText = String.format(Locale.US, "%.1f W", power)
                lastConnectionText = "Plug connected"

                if (power > offThreshold) {
                    state = "RUNNING"
                    belowThresholdSince = null
                    if (cycleFinishedLocked) {
                        cycleFinishedLocked = false
                    }
                    lastStatusText = "Running"
                    stopAlarmSound()
                } else {
                    if (state == "RUNNING") {
                        val since = belowThresholdSince
                        if (since == null) {
                            belowThresholdSince = System.currentTimeMillis()
                            lastStatusText = "Running"
                        } else if (System.currentTimeMillis() - since >= debounceSeconds * 1000L) {
                            state = "WAITING"
                            belowThresholdSince = null
                            cycleFinishedLocked = true
                            lastStatusText = "Cycle finished"
                            startAlarmSound()
                        } else {
                            lastStatusText = "Running"
                        }
                    } else {
                        lastStatusText =
                            if (cycleFinishedLocked) "Cycle finished" else "Waiting"
                        belowThresholdSince = null
                    }
                }

                updateStatusNotification()
                Thread.sleep(POLL_MS)

            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                lastConnectionText = "Connection error — retrying"
                lastPowerText = "-- W"
                updateStatusNotification()

                try {
                    client?.close()
                } catch (_: Exception) {
                }

                try {
                    Thread.sleep(ERROR_BACKOFF_MS)
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
            alarmPlayer = MediaPlayer().apply {
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
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID_STATUS,
            buildStatusNotification("$lastStatusText — $lastPowerText")
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
