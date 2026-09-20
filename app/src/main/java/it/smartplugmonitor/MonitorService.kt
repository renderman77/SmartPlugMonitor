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
        private const val CHANNEL_STATUS_ID = "monitor_status_v12"
        private const val NOTIFICATION_ID_STATUS = 1

        /** Intervallo di lettura attiva: nella fascia 5-10s indicata
         *  come sicura dalla documentazione di TinyTuya per prese con
         *  monitoraggio energia. Ogni lettura è una richiesta reale:
         *  nessun heartbeat separato necessario, la connessione resta
         *  viva da sola. */
        private const val POLL_MS = 7_000L
        private const val ERROR_BACKOFF_MS = 5_000L

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
    private var currentProfileName: String = ""

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

        client?.close()

        workerThread?.interrupt()
        try {
            workerThread?.join(3000)
        } catch (_: InterruptedException) {
        }
        workerThread = null
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

        val activeProfile = ProfileStore.getActiveProfile(this)
        val offThreshold = activeProfile.offThreshold
        val debounceSeconds = activeProfile.debounceSeconds
        currentProfileName = activeProfile.name.ifBlank { "Profile" }

        client = TuyaClient(ip, localKey)

        var state = "WAITING"
        var belowThresholdSince: Long? = null

        while (running) {
            try {

                val power = client!!.getPower()

                lastPowerText = String.format(Locale.US, "%.1f W", power)
                lastConnectionText = "Plug connected"

                if (power > offThreshold) {
                    recordRecoveryIfNeeded(belowThresholdSince, activeProfile)
                    state = "RUNNING"
                    belowThresholdSince = null
                    cycleFinishedLocked = false
                    lastStatusText = "Running"
                    stopAlarmSound()
                } else if (state == "RUNNING") {
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
                    lastStatusText = if (cycleFinishedLocked) "Cycle finished" else "Waiting"
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

    /**
     * Se stavamo contando una pausa (era sotto soglia da un po') e ora
     * il carico è tornato sopra soglia, la pausa è "recuperata" — cioè
     * non ha fatto scattare Cycle Finished. Se la calibrazione è
     * attiva su questo profilo, registriamo la durata se è la più
     * lunga vista finora, per aiutare a scegliere Duration.
     */
    private fun recordRecoveryIfNeeded(belowSince: Long?, profile: AppProfile) {
        if (belowSince == null || !profile.calibrationEnabled) return

        val pauseSeconds = (System.currentTimeMillis() - belowSince) / 1000

        if (profile.maxPauseSeconds == null || pauseSeconds > profile.maxPauseSeconds!!) {
            profile.maxPauseSeconds = pauseSeconds

            val allProfiles = ProfileStore.loadProfiles(this)
            val idx = allProfiles.indexOfFirst { it.id == profile.id }
            if (idx >= 0) {
                allProfiles[idx] = profile
                ProfileStore.saveProfiles(this, allProfiles)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefix = if (currentProfileName.isNotBlank()) "$currentProfileName — " else ""
        manager.notify(
            NOTIFICATION_ID_STATUS,
            buildStatusNotification("$prefix$lastStatusText — $lastPowerText")
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
