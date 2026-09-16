package it.smartplugmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class MonitorService : Service() {

    companion object {

        const val ACTION_UPDATE =
            "it.smartplugmonitor.MONITOR_UPDATE"

        const val ACTION_STOP_ALARM =
            "it.smartplugmonitor.STOP_ALARM"

        const val EXTRA_POWER = "power"
        const val EXTRA_STATE = "state"
        const val EXTRA_CONNECTION = "connection"

        private const val CHANNEL_ID =
            "smartplug_monitor"

        private const val NOTIFICATION_ID = 1001
        private const val FINISHED_NOTIFICATION_ID = 1002

        private const val DEFAULT_THRESHOLD = 10.0
        private const val DEFAULT_DEBOUNCE = 90L
        private const val DEFAULT_POLL = 5L

        private const val ALARM_INTERVAL = 5500L
    }

    private var client: TuyaClient? = null

    private val handler =
        Handler(Looper.getMainLooper())

    private var running = true

    private var cycleRunning = false
    private var lowSince = 0L
    private var cycleFinished = false

    private var alarm: android.media.Ringtone? = null

    private val monitorRunnable =
        object : Runnable {

            override fun run() {

                if (!running) {
                    return
                }

                readPower()
            }
        }

    private val alarmRunnable =
        object : Runnable {

            override fun run() {

                if (!running || !cycleFinished) {
                    return
                }

                playAlarmOnce()

                handler.postDelayed(
                    this,
                    ALARM_INTERVAL
                )
            }
        }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                "Monitoraggio presa attivo"
            )
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (
            intent?.action ==
            ACTION_STOP_ALARM
        ) {

            stopAlarm()

            cycleFinished = false

            getSystemService(
                NotificationManager::class.java
            ).cancel(
                FINISHED_NOTIFICATION_ID
            )

            sendUpdate(
                0.0,
                "MONITORAGGIO ATTIVO",
                "Presa collegata"
            )

            return START_STICKY
        }

        running = true

        if (
            !handler.hasCallbacks(
                monitorRunnable
            )
        ) {
            handler.post(
                monitorRunnable
            )
        }

        return START_STICKY
    }

    private fun readPower() {

        val preferences =
            getSharedPreferences(
                "settings",
                MODE_PRIVATE
            )

        val ip =
            preferences.getString(
                "ip_address",
                ""
            ) ?: ""

        val deviceId =
            preferences.getString(
                "device_id",
                ""
            ) ?: ""

        val localKey =
            preferences.getString(
                "local_key",
                ""
            ) ?: ""

        if (
            ip.isEmpty() ||
            deviceId.isEmpty() ||
            localKey.isEmpty()
        ) {

            sendUpdate(
                0.0,
                "NON CONFIGURATO",
                "Configura la presa nelle impostazioni"
            )

            scheduleNextRead()

            return
        }

        if (client == null) {

            client =
                TuyaClient(
                    deviceId,
                    ip,
                    localKey
                )
        }

        Thread {

            try {

                val power =
                    client!!.getPower()

                handler.post {

                    processPower(
                        power,
                        preferences
                    )

                    scheduleNextRead()
                }

            } catch (e: Exception) {

                client?.close()
                client = null

                handler.post {

                    sendUpdate(
                        0.0,
                        "PRESA NON RAGGIUNGIBILE",
                        e.message
                            ?: "Errore di connessione"
                    )

                    scheduleNextRead()
                }
            }

        }.start()
    }

    private fun processPower(
        power: Double,
        preferences:
            android.content.SharedPreferences
    ) {

        val threshold =
            preferences.getString(
                "off_threshold",
                DEFAULT_THRESHOLD.toString()
            )
                ?.toDoubleOrNull()
                ?: DEFAULT_THRESHOLD

        val debounce =
            preferences.getString(
                "debounce_seconds",
                DEFAULT_DEBOUNCE.toString()
            )
                ?.toLongOrNull()
                ?.coerceAtLeast(1)
                ?: DEFAULT_DEBOUNCE

        if (power >= threshold) {

            cycleRunning = true
            lowSince = 0L

            if (cycleFinished) {

                cycleFinished = false

                stopAlarm()

                getSystemService(
                    NotificationManager::class.java
                ).cancel(
                    FINISHED_NOTIFICATION_ID
                )
            }

            sendUpdate(
                power,
                "IN FUNZIONE",
                "Presa collegata"
            )

            updateNotification(power)

            return
        }

        if (cycleRunning) {

            if (lowSince == 0L) {

                lowSince =
                    System.currentTimeMillis()
            }

            val elapsed =
                (
                    System.currentTimeMillis() -
                        lowSince
                ) / 1000L

            if (elapsed >= debounce) {

                cycleRunning = false
                cycleFinished = true
                lowSince = 0L

               sendUpdate(
    power,
    "CICLO TERMINATO",
    "Presa collegata"
)

updateNotification(power)

startAlarm()

showFinishedNotification()

val finishIntent =
    Intent(
        this,
        FineCycleActivity::class.java
    ).apply {
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
    }

startActivity(finishIntent)

            } else {

                sendUpdate(
                    power,
                    "IN FUNZIONE",
                    "Presa collegata"
                )

                updateNotification(power)
            }

            return
        }

        sendUpdate(
            power,
            "MONITORAGGIO ATTIVO",
            "Presa collegata"
        )

        updateNotification(power)
    }

    private fun scheduleNextRead() {

        val preferences =
            getSharedPreferences(
                "settings",
                MODE_PRIVATE
            )

        val interval =
            preferences.getString(
                "poll_interval",
                DEFAULT_POLL.toString()
            )
                ?.toLongOrNull()
                ?.coerceAtLeast(1)
                ?: DEFAULT_POLL

        handler.removeCallbacks(
            monitorRunnable
        )

        handler.postDelayed(
            monitorRunnable,
            interval * 1000L
        )
    }

    private fun startAlarm() {

        handler.removeCallbacks(
            alarmRunnable
        )

        playAlarmOnce()

        handler.postDelayed(
            alarmRunnable,
            ALARM_INTERVAL
        )
    }

    private fun playAlarmOnce() {

        try {

            val uri =
                android.media.RingtoneManager
                    .getDefaultUri(
                        android.media.RingtoneManager.TYPE_ALARM
                    )

            alarm?.stop()

            alarm =
                android.media.RingtoneManager
                    .getRingtone(
                        applicationContext,
                        uri
                    )

            alarm?.play()

        } catch (_: Exception) {
        }
    }

    private fun stopAlarm() {

        handler.removeCallbacks(
            alarmRunnable
        )

        try {
            alarm?.stop()
        } catch (_: Exception) {
        }

        alarm = null
    }

    private fun sendUpdate(
        power: Double,
        state: String,
        connection: String
    ) {

        val intent =
            Intent(ACTION_UPDATE).apply {

                setPackage(packageName)

                putExtra(
                    EXTRA_POWER,
                    power
                )

                putExtra(
                    EXTRA_STATE,
                    state
                )

                putExtra(
                    EXTRA_CONNECTION,
                    connection
                )
            }

        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Smart Plug Monitor",
                NotificationManager.IMPORTANCE_HIGH
            )

        channel.description =
            "Avvisi del monitoraggio della presa"

        manager.createNotificationChannel(
            channel
        )
    }

    private fun buildNotification(
        text: String
    ): Notification {

        val intent =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_dialog_info
            )
            .setContentTitle(
                "Smart Plug Monitor"
            )
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showFinishedNotification() {

        val intent =
            Intent(
                this,
                FineCycleActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_alert
                )
                .setContentTitle(
                    "CICLO TERMINATO"
                )
                .setContentText(
                    "Premi per aprire l'avviso"
                )
                .setPriority(
                    NotificationCompat.PRIORITY_MAX
                )
                .setCategory(
                    NotificationCompat.CATEGORY_ALARM
                )
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(
                    pendingIntent
                )
                .build()

        getSystemService(
            NotificationManager::class.java
        ).notify(
            FINISHED_NOTIFICATION_ID,
            notification
        )
    }

    private fun updateNotification(
        power: Double
    ) {

        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            buildNotification(
                String.format(
                    java.util.Locale.US,
                    "Monitoraggio: %.1f W",
                    power
                )
            )
        )
    }

    override fun onDestroy() {

        running = false

        handler.removeCallbacksAndMessages(
            null
        )

        stopAlarm()

        client?.close()
        client = null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
