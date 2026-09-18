package it.smartplugmonitor

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import java.util.Locale

class MonitorService : Service() {

    companion object {
        private const val CHANNEL_STATUS_ID = "monitor_status_v9"
        private const val CHANNEL_ALERT_ID = "monitor_alert_v9"
        private const val NOTIFICATION_ID_STATUS = 1
        private const val NOTIFICATION_ID_ALERT = 2

        @Volatile var isServiceRunning = false
        @Volatile var lastPowerText = "-- W"
        @Volatile var lastStatusText = "Waiting to start"
        @Volatile var lastConnectionText = "Not connected yet"
    }

    @Volatile private var running = false
    private var workerThread: Thread? = null
    private var client: TuyaClient? = null
    @Volatile private var alertActive = false
    @Volatile private var cycleFinishedLocked = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Bug fix: in the previous version this was never called, so on
        // a fresh install the notification channels didn't exist yet and
        // notifications could silently fail to appear.
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == "STOP_ALARM_ACTION") {
            // Also fully stops monitoring, as requested: this is no
            // longer just a "silence the alarm" button.
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID_STATUS, buildStatusNotification("Starting..."))

        if (workerThread?.isAlive == true) {
            return START_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Fix: the previous version acquired this wake lock for a fixed
        // 15 minutes, then let it expire automatically -- on a wash
        // cycle longer than that (very common), the CPU could go back
        // to sleep mid-cycle while the screen was off, which is the
        // most likely cause of the "much slower with the screen off"
        // symptom. Held indefinitely now, tied to the service's own
        // lifecycle instead of a fixed timer, and released in onDestroy.
        wakeLock =
            pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmartPlugMonitor::WakeLock"
            ).apply { acquire() }

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

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Bug fix: previously only the status notification (1) was
        // cancelled here, so if you stopped monitoring while the alarm
        // was actively ringing, the alarm notification (2) kept going.
        manager.cancel(NOTIFICATION_ID_STATUS)
        manager.cancel(NOTIFICATION_ID_ALERT)

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

        val basePollMs = 10_000L

        client = TuyaClient(ip, localKey)

        var state = "WAITING"
        var belowThresholdSince: Long? = null

        while (running) {

            var sleepTime = basePollMs

            try {

                val power = client!!.getPower()

                lastPowerText = String.format(Locale.US, "%.1f W", power)
                lastConnectionText = "Plug connected"

                if (power > offThreshold) {

                    state = "RUNNING"
                    belowThresholdSince = null
                    cycleFinishedLocked = false
                    lastStatusText = "Running"

                    if (alertActive) {
                        cancelAlertNotification()
                        alertActive = false
                    }

                } else {

                    if (state == "RUNNING") {

                        sleepTime = 3_000L

                        val since = belowThresholdSince

                        if (since == null) {
                            belowThresholdSince = System.currentTimeMillis()
                            lastStatusText = "Running"
                        } else if (
                            System.currentTimeMillis() - since >= debounceSeconds * 1000L
                        ) {
                            sendCycleFinishedNotification(prefs)
                            alertActive = true
                            state = "WAITING"
                            belowThresholdSince = null
                            cycleFinishedLocked = true
                            lastStatusText = "Cycle finished"
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
                    Thread.sleep(5_000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun sendCycleFinishedNotification(prefs: SharedPreferences) {

        val title = prefs.getString("alert_title", "Cycle finished") ?: "Cycle finished"

        val message =
            prefs.getString("alert_message", "The appliance has finished.")
                ?: "The appliance has finished."

        val stopIntent =
            Intent(this, MonitorService::class.java).apply {
                action = "STOP_ALARM_ACTION"
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val soundUri = Uri.parse("android.resource://$packageName/raw/alarm_beep")

        val notification =
            NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setSound(soundUri)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "STOP",
                    stopPendingIntent
                )
                .build()

        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(NOTIFICATION_ID_ALERT, notification)
    }

    private fun cancelAlertNotification() {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_ALERT)
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

        val soundUri = Uri.parse("android.resource://$packageName/raw/alarm_beep")

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT_ID,
                "Cycle finished",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setBypassDnd(true)
                enableLights(false)
                enableVibration(false)
            }
        )
    }
}
