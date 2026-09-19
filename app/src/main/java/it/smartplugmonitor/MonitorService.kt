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

        /** Intervallo confermato sicuro da un test reale (misurato su
         *  prese dello stesso tipo, protocollo 3.4): senza battito la
         *  connessione cade dopo ~30s di inattività. Lo mandiamo ogni
         *  20s dall'ULTIMO scambio reale (non a orario fisso), così se
         *  arriva già qualcosa di spontaneo non serve mandarne uno in più. */
        private const val HEARTBEAT_INTERVAL_MS = 20_000L

        /** Quanto restare in ascolto passivo prima di ridare il
         *  controllo al ciclo (per poter comunque controllare il
         *  debounce e il timer dell'heartbeat). */
        private const val LISTEN_TIMEOUT_MS = 8_000
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

        // Importante l'ordine: chiudere il socket PRIMA sblocca subito
        // il thread se è fermo in lettura (interrupt() da solo non lo
        // farebbe, si sbloccherebbe solo al timeout, fino a 8 secondi).
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

        val offThreshold =
            (prefs.getString("off_threshold", "10") ?: "10").toDoubleOrNull() ?: 10.0
        val debounceSeconds =
            (prefs.getString("debounce_seconds", "10") ?: "10").toLongOrNull() ?: 10L

        client = TuyaClient(ip, localKey)

        // Schiaffo iniziale (una tantum): chiediamo un aggiornamento
        // forzato per sapere subito lo stato di partenza, invece di
        // aspettare che la presa mandi qualcosa di sua iniziativa.
        // Se non contiene la potenza, ripieghiamo su una lettura normale.
        var state = "WAITING"
        try {
            val fresh = client!!.requestFreshPower() ?: client!!.getPower()
            lastPowerText = String.format(Locale.US, "%.1f W", fresh)
            if (fresh > offThreshold) {
                state = "RUNNING"
                lastStatusText = "Running"
            } else {
                lastStatusText = "Waiting"
            }
        } catch (_: Exception) {
        }

        var belowThresholdSince: Long? = null
        var lastExchange = System.currentTimeMillis()

        while (running) {
            try {

                // Nessuna richiesta di dati: restiamo in ascolto. Se la
                // presa manda qualcosa di sua iniziativa entro la
                // finestra, lo vediamo; altrimenti "power" è null e va
                // bene così, non è un errore.
                val power = client!!.listenForUpdate(LISTEN_TIMEOUT_MS)

                // Se nel frattempo è arrivato lo STOP (onDestroy ha già
                // chiuso il socket per sbloccarci), usciamo subito senza
                // toccare più heartbeat o notifica.
                if (!running) break

                lastConnectionText = "Plug connected"

                if (power != null) {
                    // Un vero scambio è appena avvenuto: il prossimo
                    // heartbeat può aspettare, non serve mandarne uno
                    // in più adesso.
                    lastExchange = System.currentTimeMillis()

                    lastPowerText = String.format(Locale.US, "%.1f W", power)

                    if (power > offThreshold) {
                        state = "RUNNING"
                        belowThresholdSince = null
                        cycleFinishedLocked = false
                        lastStatusText = "Running"
                        stopAlarmSound()
                    } else if (state == "RUNNING" && belowThresholdSince == null) {
                        belowThresholdSince = System.currentTimeMillis()
                        lastStatusText = "Running"
                    }
                }

                // Il controllo del debounce va rifatto a ogni ciclo,
                // anche quando non arriva nessun dato nuovo: è il tempo
                // trascorso che conta, non l'ultimo messaggio ricevuto.
                val since = belowThresholdSince
                if (state == "RUNNING" && since != null &&
                    System.currentTimeMillis() - since >= debounceSeconds * 1000L
                ) {
                    state = "WAITING"
                    belowThresholdSince = null
                    cycleFinishedLocked = true
                    lastStatusText = "Cycle finished"
                    startAlarmSound()
                } else if (state == "WAITING" && power == null) {
                    lastStatusText = if (cycleFinishedLocked) "Cycle finished" else "Waiting"
                }

                // Battito di mantenimento SOLO se non c'è stato nessuno
                // scambio reale (spontaneo o battito precedente) negli
                // ultimi 20 secondi.
                if (System.currentTimeMillis() - lastExchange >= HEARTBEAT_INTERVAL_MS) {
                    client!!.sendHeartbeat()
                    lastExchange = System.currentTimeMillis()
                }

                updateStatusNotification()

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
