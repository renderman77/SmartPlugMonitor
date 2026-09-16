package it.smartplugmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Locale

class MonitorService : Service() {

    companion object {
        const val ACTION_STOP_ALERT =
            "it.smartplugmonitor.STOP_ALERT"

        const val ACTION_NEW_CYCLE =
            "it.smartplugmonitor.NEW_CYCLE"

        private const val CHANNEL_ID =
            "smartplug_monitor"

        private const val NOTIFICATION_ID = 1001

        private const val THRESHOLD_DEFAULT = 10.0
        private const val DEBOUNCE_DEFAULT = 90L
        private const val POLL_DEFAULT = 5L
    }

    private var monitorThread: Thread? = null

    @Volatile
    private var running = true

    private var client: TuyaClient? = null

    @Volatile
    private var alertActive = false

    private var alertPlayer: MediaPlayer? = null
    private var alertThread: Thread? = null

    private enum class State {
        ATTESA,
        IN_FUNZIONE,
        CONTEGGIO_FINE,
        CICLO_TERMINATO
    }

    private var state = State.ATTESA

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Monitoraggio attivo")
        )

        startMonitoring()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP_ALERT) {
            stopAlert()
        }

        return START_STICKY
    }

    private fun startMonitoring() {

        if (monitorThread?.isAlive == true) {
            return
        }

        val preferences =
            getSharedPreferences("settings", MODE_PRIVATE)

        val ip =
            preferences.getString("ip_address", "") ?: ""

        val deviceId =
            preferences.getString("device_id", "") ?: ""

        val localKey =
            preferences.getString("local_key", "") ?: ""

        val threshold =
            preferences.getString(
                "off_threshold",
                THRESHOLD_DEFAULT.toString()
            )
                ?.replace(",", ".")
                ?.toDoubleOrNull()
                ?: THRESHOLD_DEFAULT

        val debounceSeconds =
            preferences.getString(
                "debounce_seconds",
                DEBOUNCE_DEFAULT.toString()
            )
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: DEBOUNCE_DEFAULT

        val pollInterval =
            preferences.getString(
                "poll_interval",
                POLL_DEFAULT.toString()
            )
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: POLL_DEFAULT

        if (
            ip.isEmpty() ||
            deviceId.isEmpty() ||
            localKey.isEmpty()
        ) {
            updateNotification("Presa non configurata")
            return
        }

        client = TuyaClient(
            deviceId,
            ip,
            localKey
        )

        monitorThread = Thread {

            var lowPowerStart: Long? = null

            while (running) {

                try {

                    val power =
                        client?.getPower()
                            ?: throw Exception(
                                "Connessione assente"
                            )

                    android.util.Log.d(
                        "SmartPlugMonitor",
                        String.format(
                            Locale.US,
                            "POWER %.1f W",
                            power
                        )
                    )

                    when (state) {

                        State.ATTESA -> {

                            if (power >= threshold) {

                                state =
                                    State.IN_FUNZIONE

                                lowPowerStart = null

                                updateNotification(
                                    String.format(
                                        Locale.US,
                                        "In funzione — %.1f W",
                                        power
                                    )
                                )
                            }
                        }

                        State.IN_FUNZIONE -> {

                            if (power >= threshold) {

                                lowPowerStart = null

                                updateNotification(
                                    String.format(
                                        Locale.US,
                                        "In funzione — %.1f W",
                                        power
                                    )
                                )

                            } else {

                                lowPowerStart =
                                    System.currentTimeMillis()

                                state =
                                    State.CONTEGGIO_FINE

                                updateNotification(
                                    "Monitoraggio attivo"
                                )
                            }
                        }

                        State.CONTEGGIO_FINE -> {

                            if (power >= threshold) {

                                /*
                                 * Il carico è ripartito:
                                 * annulliamo completamente
                                 * il conteggio e qualsiasi
                                 * eventuale avviso.
                                 */
                                lowPowerStart = null

                                state =
                                    State.IN_FUNZIONE

                                stopAlert()

                                sendNewCycleBroadcast()

                                updateNotification(
                                    String.format(
                                        Locale.US,
                                        "In funzione — %.1f W",
                                        power
                                    )
                                )

                            } else {

                                val start =
                                    lowPowerStart
                                        ?: System.currentTimeMillis()

                                lowPowerStart = start

                                val elapsed =
                                    (
                                        System.currentTimeMillis()
                                            - start
                                        ) / 1000L

                                if (
                                    elapsed >=
                                    debounceSeconds
                                ) {

                                    state =
                                        State.CICLO_TERMINATO

                                    lowPowerStart = null

                                    startAlert()

                                    updateNotification(
                                        "Ciclo terminato"
                                    )
                                }
                            }
                        }

                        State.CICLO_TERMINATO -> {

                            if (power >= threshold) {

                                /*
                                 * Nuovo ciclo:
                                 * l'avviso viene cancellato
                                 * automaticamente anche se
                                 * l'utente non ha premuto OK.
                                 */
                                stopAlert()

                                sendNewCycleBroadcast()

                                state =
                                    State.IN_FUNZIONE

                                updateNotification(
                                    String.format(
                                        Locale.US,
                                        "In funzione — %.1f W",
                                        power
                                    )
                                )

                            } else {

                                updateNotification(
                                    "Ciclo terminato"
                                )
                            }
                        }
                    }

                } catch (e: Exception) {

                    android.util.Log.e(
                        "SmartPlugMonitor",
                        "Errore lettura: ${e.message}",
                        e
                    )

                    /*
                     * Chiudiamo la sessione solo se è realmente
                     * fallita. Il prossimo tentativo ricrea
                     * automaticamente la connessione.
                     */
                    client?.close()

                    updateNotification(
                        "Riconnessione presa..."
                    )

                    /*
                     * Non aspettiamo inutilmente 5 secondi
                     * dopo un errore: tentiamo rapidamente
                     * la riconnessione.
                     */
                    try {
                        Thread.sleep(1000L)
                    } catch (_: InterruptedException) {
                        break
                    }

                    continue
                }

                try {

                    Thread.sleep(
                        pollInterval * 1000L
                    )

                } catch (_: InterruptedException) {
                    break
                }
            }

        }.also {
            it.start()
        }
    }

    private fun startAlert() {

        if (alertActive) {
            return
        }

        alertActive = true

        getSharedPreferences(
            "settings",
            MODE_PRIVATE
        )
            .edit()
            .putBoolean("alert_active", true)
            .apply()

        showAlertNotification()
        startAlertSound()
    }

    private fun stopAlert() {

        if (!alertActive) {
            return
        }

        alertActive = false

        getSharedPreferences(
            "settings",
            MODE_PRIVATE
        )
            .edit()
            .putBoolean("alert_active", false)
            .apply()

        alertThread?.interrupt()
        alertThread = null

        try {
            alertPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            alertPlayer?.release()
        } catch (_: Exception) {
        }

        alertPlayer = null

        val notificationManager =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager

        notificationManager.cancel(
            NOTIFICATION_ID + 1
        )

        sendStopAlertBroadcast()
    }

    private fun startAlertSound() {

        alertThread = Thread {

            while (
                running &&
                alertActive &&
                !Thread.currentThread().isInterrupted
            ) {

                try {

                    playAlarmBurst()

                    /*
                     * Due segnali ravvicinati, poi pausa
                     * di circa 5,5 secondi.
                     */
                    Thread.sleep(5500L)

                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also {
            it.start()
        }
    }

    private fun playAlarmBurst() {

        val alarmUri =
            android.media.RingtoneManager
                .getDefaultUri(
                    android.media.RingtoneManager.TYPE_ALARM
                )

        try {

            alertPlayer?.release()

            alertPlayer =
                MediaPlayer.create(
                    this,
                    alarmUri
                )

            alertPlayer?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_ALARM
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SONIFICATION
                    )
                    .build()
            )

            alertPlayer?.start()

            /*
             * Primo segnale.
             */
            Thread.sleep(700L)

            alertPlayer?.stop()
            alertPlayer?.release()
            alertPlayer = null

            /*
             * Secondo segnale.
             */
            Thread.sleep(180L)

            if (!alertActive) {
                return
            }

            alertPlayer =
                MediaPlayer.create(
                    this,
                    alarmUri
                )

            alertPlayer?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_ALARM
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SONIFICATION
                    )
                    .build()
            )

            alertPlayer?.start()

            Thread.sleep(700L)

            alertPlayer?.stop()
            alertPlayer?.release()
            alertPlayer = null

        } catch (_: Exception) {
        }
    }

    private fun showAlertNotification() {

        val intent =
            Intent(
                this,
                FineCycleActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                200,
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
                    "Premi OK per fermare l'avviso"
                )
                .setPriority(
                    NotificationCompat.PRIORITY_MAX
                )
                .setCategory(
                    NotificationCompat.CATEGORY_ALARM
                )
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(
                    pendingIntent,
                    true
                )
                .build()

        val manager =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager

        manager.notify(
            NOTIFICATION_ID + 1,
            notification
        )
    }

    private fun sendNewCycleBroadcast() {

        sendBroadcast(
            Intent(ACTION_NEW_CYCLE)
        )
    }

    private fun sendStopAlertBroadcast() {

        sendBroadcast(
            Intent(ACTION_STOP_ALERT)
        )
    }

    private fun updateNotification(text: String) {

        val notification =
            buildNotification(text)

        val manager =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            notification
        )
    }

    private fun buildNotification(
        text: String
    ): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_menu_info_details
            )
            .setContentTitle(
                "Smart Plug Monitor"
            )
            .setContentText(text)
            .setOngoing(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()
    }

    private fun createNotificationChannel() {

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Monitoraggio presa",
                NotificationManager.IMPORTANCE_LOW
            )

        channel.description =
            "Monitoraggio continuo della smart plug"

        val manager =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager

        manager.createNotificationChannel(
            channel
        )
    }

    override fun onDestroy() {

        running = false

        monitorThread?.interrupt()
        monitorThread = null

        alertThread?.interrupt()
        alertThread = null

        try {
            alertPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            alertPlayer?.release()
        } catch (_: Exception) {
        }

        alertPlayer = null

        client?.close()
        client = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
