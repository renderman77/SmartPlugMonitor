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

/**
 * Due fasi di funzionamento, per bilanciare reattività e risparmio
 * energetico su un ciclo che può durare 3-4 ore:
 *
 * - NORMAL: interrogazione attiva (UPDATEDPS) a intervalli, es. ogni
 *   20 secondi. La CPU si sveglia solo per l'istante della richiesta
 *   (WakeLock brevissimo, preso e rilasciato subito), poi può tornare
 *   a dormire in profondità fino al prossimo giro.
 *
 * - ALERT: ascolto passivo continuo (nessuna richiesta di dati, solo
 *   un battito di mantenimento periodico), per la massima precisione.
 *   Si entra in questa fase quando: il carico è appena sceso sotto
 *   soglia (stiamo contando per Cycle Finished) oppure l'app è in
 *   primo piano (l'utente sta guardando lo schermo). In questa fase
 *   sì si tiene un WakeLock continuo, ma solo per la durata —
 *   normalmente breve — di questa fase, non per l'intero ciclo.
 */
class MonitorService : Service() {

    companion object {
        private const val CHANNEL_STATUS_ID = "monitor_status_v13"
        private const val NOTIFICATION_ID_STATUS = 1

        private const val ALERT_LISTEN_TIMEOUT_MS = 2_000
        private const val ALERT_HEARTBEAT_MS = 15_000L
        private const val ERROR_BACKOFF_MS = 5_000L

        /** Tetto di sicurezza per il WakeLock breve della fase NORMAL:
         *  si rilascia comunque subito dopo la lettura, questo è solo
         *  una rete di sicurezza nel caso qualcosa si blocchi. */
        private const val NORMAL_WAKELOCK_SAFETY_MS = 8_000L

        @Volatile var isServiceRunning = false
        @Volatile var lastPowerText = "-- W"
        @Volatile var lastStatusText = "Stopped"
        @Volatile var lastConnectionText = "Not connected"
    }

    @Volatile private var running = false
    @Volatile private var foregroundRequested = false

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

        // Segnali "leggeri": l'app in primo piano ci dice di passare
        // in modalità più reattiva, non riavviano nulla.
        when (intent?.action) {
            "APP_FOREGROUND" -> {
                foregroundRequested = true
                return START_STICKY
            }
            "APP_BACKGROUND" -> {
                foregroundRequested = false
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID_STATUS, buildStatusNotification("Starting..."))

        if (workerThread?.isAlive == true) {
            return START_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmartPlugMonitor::WakeLock"
        ).apply { setReferenceCounted(false) }

        running = true
        isServiceRunning = true
        foregroundRequested = false
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

        val normalIntervalSeconds =
            (prefs.getString("normal_interval_seconds", "20") ?: "20").toIntOrNull() ?: 20
        val normalIntervalMs = normalIntervalSeconds * 1000L

        client = TuyaClient(ip, localKey)

        var state = "WAITING"
        var belowThresholdSince: Long? = null
        var belowThresholdDetectedInNormalMode = false
        var currentMode = "NORMAL"
        var lastExchange = System.currentTimeMillis()

        fun desiredMode(): String =
            if ((state == "RUNNING" && belowThresholdSince != null) || foregroundRequested) "ALERT" else "NORMAL"

        while (running) {
            try {

                val mode = desiredMode()
                if (mode != currentMode) {
                    if (mode == "ALERT") {
                        wakeLock?.acquire()
                        lastExchange = System.currentTimeMillis()
                    } else {
                        try {
                            if (wakeLock?.isHeld == true) wakeLock?.release()
                        } catch (_: Exception) {
                        }
                    }
                    currentMode = mode
                }

                var power: Double? = null

                if (currentMode == "ALERT") {
                    power = client!!.listenForUpdate(ALERT_LISTEN_TIMEOUT_MS)
                    lastConnectionText = "Plug connected"

                    if (System.currentTimeMillis() - lastExchange >= ALERT_HEARTBEAT_MS) {
                        client!!.sendHeartbeat()
                        lastExchange = System.currentTimeMillis()
                    }
                } else {
                    try {
                        wakeLock?.acquire(NORMAL_WAKELOCK_SAFETY_MS)
                        power = client!!.getPower()
                    } finally {
                        try {
                            if (wakeLock?.isHeld == true) wakeLock?.release()
                        } catch (_: Exception) {
                        }
                    }
                    lastConnectionText = "Plug connected"
                }

                if (power != null) {
                    lastPowerText = String.format(Locale.US, "%.1f W", power)

                    if (power > offThreshold) {
                        val marginSeconds = if (belowThresholdDetectedInNormalMode) normalIntervalSeconds.toLong() else 0L
                        recordRecoveryIfNeeded(belowThresholdSince, activeProfile, marginSeconds)
                        state = "RUNNING"
                        belowThresholdSince = null
                        cycleFinishedLocked = false
                        lastStatusText = "Running"
                        stopAlarmSound()
                    } else if (state == "RUNNING" && belowThresholdSince == null) {
                        belowThresholdSince = System.currentTimeMillis()
                        belowThresholdDetectedInNormalMode = (currentMode == "NORMAL")
                        lastStatusText = "Running"
                    }
                }

                // Il controllo del debounce va rifatto a ogni ciclo,
                // anche senza un dato nuovo: conta il tempo trascorso.
                val since = belowThresholdSince
                if (state == "RUNNING" && since != null &&
                    System.currentTimeMillis() - since >= debounceSeconds * 1000L
                ) {
                    state = "WAITING"
                    belowThresholdSince = null
                    cycleFinishedLocked = true
                    lastStatusText = "Cycle finished"
                    startAlarmSound()
                } else if (state == "WAITING") {
                    lastStatusText = if (cycleFinishedLocked) "Cycle finished" else "Waiting"
                }

                updateStatusNotification()

                if (currentMode == "NORMAL") {
                    Thread.sleep(normalIntervalMs)
                }
                // In ALERT il timeout di listenForUpdate scandisce già
                // il ritmo del ciclo, nessuna attesa aggiuntiva serve.

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
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                } catch (_: Exception) {
                }

                try {
                    Thread.sleep(ERROR_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
    }

    /**
     * Se stavamo contando una pausa e ora il carico è tornato sopra
     * soglia, la pausa è "recuperata". Se la calibrazione è attiva,
     * registriamo la durata (più un margine di sicurezza se la pausa
     * era stata rilevata durante la fase NORMAL: in quel caso il vero
     * inizio della pausa può essere avvenuto fino a un intero
     * intervallo prima di quando ce ne siamo accorti).
     */
    private fun recordRecoveryIfNeeded(belowSince: Long?, profile: AppProfile, marginSeconds: Long) {
        if (belowSince == null || !profile.calibrationEnabled) return

        val observedSeconds = (System.currentTimeMillis() - belowSince) / 1000
        val adjustedSeconds = observedSeconds + marginSeconds

        if (profile.maxPauseSeconds == null || adjustedSeconds > profile.maxPauseSeconds!!) {
            profile.maxPauseSeconds = adjustedSeconds

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
