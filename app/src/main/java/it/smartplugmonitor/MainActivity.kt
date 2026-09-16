package it.smartplugmonitor

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var client: TuyaClient? = null
    private var monitorThread: Thread? = null

    private lateinit var statusText: TextView
    private lateinit var powerText: TextView
    private lateinit var connectionText: TextView

    @Volatile
    private var running = false

    private enum class State {
        ATTESA,
        IN_FUNZIONE,
        CONTEGGIO_FINE,
        CICLO_TERMINATO
    }

    private var state = State.ATTESA

    private val logFile: File by lazy {
        File(filesDir, "monitor_log.txt")
    }

    private val logFormat =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        statusText =
            findViewById(R.id.statusText)

        powerText =
            findViewById(R.id.powerText)

        connectionText =
            findViewById(R.id.connectionText)

        findViewById<ImageButton>(R.id.settingsButton)
            .setOnClickListener {

                startActivity(
                    Intent(
                        this,
                        SettingsActivity::class.java
                    )
                )
            }

        writeLog("APP | avviata")
    }

    override fun onResume() {
        super.onResume()

        running = true

        writeLog("MONITOR | avvio monitoraggio")

        startMonitoring()
    }

    override fun onPause() {
        super.onPause()

        running = false

        writeLog("MONITOR | pausa monitoraggio")

        monitorThread?.interrupt()
        monitorThread = null

        client?.close()
        client = null
    }

    private fun startMonitoring() {

        if (monitorThread?.isAlive == true) {
            return
        }

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

            writeLog(
                "CONFIG | presa non configurata"
            )

            showNotConfigured()

            return
        }

        val threshold =
            preferences.getString(
                "off_threshold",
                "10"
            )
                ?.replace(",", ".")
                ?.toDoubleOrNull()
                ?: 10.0

        val debounceSeconds =
            preferences.getString(
                "debounce_seconds",
                "90"
            )
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: 90L

        val pollInterval =
            preferences.getString(
                "poll_interval",
                "5"
            )
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: 5L

        writeLog(
            "CONFIG | soglia=${threshold}W | " +
                    "conferma=${debounceSeconds}s | " +
                    "intervallo=${pollInterval}s"
        )

        state = State.ATTESA

        client =
            TuyaClient(
                deviceId,
                ip,
                localKey
            )

        monitorThread = Thread {

            var lowPowerStart: Long? = null

            while (running) {

                val requestTime =
                    System.currentTimeMillis()

                writeLog(
                    "REQUEST | richiesta potenza"
                )

                try {

                    val power =
                        client?.getPower()
                            ?: throw Exception(
                                "Connessione assente"
                            )

                    val responseTime =
                        System.currentTimeMillis()

                    val responseMs =
                        responseTime - requestTime

                    writeLog(
                        String.format(
                            Locale.US,
                            "POWER | %.1f W | risposta=%d ms",
                            power,
                            responseMs
                        )
                    )

                    runOnUiThread {

                        powerText.text =
                            String.format(
                                Locale.US,
                                "%.1f W",
                                power
                            )

                        connectionText.text =
                            "Presa collegata"
                    }

                    val now =
                        System.currentTimeMillis()

                    when (state) {

                        State.ATTESA -> {

                            if (power >= threshold) {

                                state =
                                    State.IN_FUNZIONE

                                lowPowerStart = null

                                writeLog(
                                    "STATE | ATTESA -> IN_FUNZIONE"
                                )

                                updateStatus(
                                    "●  IN FUNZIONE",
                                    android.R.color.holo_green_dark
                                )

                            } else {

                                updateStatus(
                                    "●  MONITORAGGIO ATTIVO",
                                    android.R.color.holo_green_dark
                                )
                            }
                        }

                        State.IN_FUNZIONE -> {

                            if (power >= threshold) {

                                lowPowerStart = null

                                updateStatus(
                                    "●  MONITORAGGIO ATTIVO",
                                    android.R.color.holo_green_dark
                                )

                            } else {

                                lowPowerStart = now

                                state =
                                    State.CONTEGGIO_FINE

                                writeLog(
                                    "STATE | IN_FUNZIONE -> " +
                                            "CONTEGGIO_FINE"
                                )

                                /*
                                 * Non mostriamo un alert
                                 * all'utente.
                                 */
                                updateStatus(
                                    "●  MONITORAGGIO ATTIVO",
                                    android.R.color.holo_green_dark
                                )
                            }
                        }

                        State.CONTEGGIO_FINE -> {

                            if (power >= threshold) {

                                /*
                                 * La potenza è risalita:
                                 * il conteggio viene completamente
                                 * cancellato.
                                 */
                                lowPowerStart = null

                                state =
                                    State.IN_FUNZIONE

                                writeLog(
                                    "STATE | CONTEGGIO_FINE -> " +
                                            "IN_FUNZIONE | " +
                                            "conteggio azzerato"
                                )

                                updateStatus(
                                    "●  IN FUNZIONE",
                                    android.R.color.holo_green_dark
                                )

                            } else {

                                val start =
                                    lowPowerStart ?: now

                                val elapsed =
                                    (now - start) / 1000L

                                writeLog(
                                    "LOW | sotto soglia da " +
                                            "${elapsed}s"
                                )

                                if (
                                    elapsed >=
                                    debounceSeconds
                                ) {

                                    state =
                                        State.CICLO_TERMINATO

                                    lowPowerStart =
                                        null

                                    writeLog(
                                        "STATE | CONTEGGIO_FINE -> " +
                                                "CICLO_TERMINATO"
                                    )

                                    updateStatus(
                                        "●  CICLO TERMINATO",
                                        android.R.color.holo_blue_dark
                                    )

                                } else {

                                    /*
                                     * Rimane semplicemente
                                     * in monitoraggio.
                                     */
                                    updateStatus(
                                        "●  MONITORAGGIO ATTIVO",
                                        android.R.color.holo_green_dark
                                    )
                                }
                            }
                        }

                        State.CICLO_TERMINATO -> {

                            /*
                             * Dopo la fine ciclo continuiamo
                             * a interrogare la presa normalmente.
                             *
                             * Se supera la soglia, è iniziato
                             * un nuovo ciclo.
                             */
                            if (power >= threshold) {

                                state =
                                    State.IN_FUNZIONE

                                lowPowerStart = null

                                writeLog(
                                    "STATE | CICLO_TERMINATO -> " +
                                            "IN_FUNZIONE | nuovo ciclo"
                                )

                                updateStatus(
                                    "●  IN FUNZIONE",
                                    android.R.color.holo_green_dark
                                )

                            } else {

                                updateStatus(
                                    "●  CICLO TERMINATO",
                                    android.R.color.holo_blue_dark
                                )
                            }
                        }
                    }

                } catch (e: Exception) {

                    val errorTime =
                        System.currentTimeMillis()

                    val errorMs =
                        errorTime - requestTime

                    writeLog(
                        "ERROR | dopo ${errorMs}ms | " +
                                "${e.javaClass.simpleName} | " +
                                "${e.message ?: "errore sconosciuto"}"
                    )

                    client?.close()

                    writeLog(
                        "CONNECTION | client chiuso dopo errore"
                    )

                    runOnUiThread {

                        statusText.text =
                            "●  PRESA NON RAGGIUNGIBILE"

                        statusText.setTextColor(
                            getColor(
                                android.R.color.holo_red_dark
                            )
                        )

                        connectionText.text =
                            e.message
                                ?: "Errore di connessione"
                    }
                }

                try {

                    writeLog(
                        "WAIT | attesa ${pollInterval}s"
                    )

                    Thread.sleep(
                        pollInterval * 1000L
                    )

                } catch (_: InterruptedException) {

                    writeLog(
                        "MONITOR | thread interrotto"
                    )

                    break
                }
            }

            writeLog(
                "MONITOR | thread terminato"
            )

        }.also {
            it.start()
        }
    }

    private fun showNotConfigured() {

        statusText.text =
            "●  NON CONFIGURATO"

        statusText.setTextColor(
            getColor(
                android.R.color.darker_gray
            )
        )

        powerText.text =
            "0.0 W"

        connectionText.text =
            "Configura la presa nelle impostazioni"
    }

    private fun updateStatus(
        text: String,
        color: Int
    ) {

        runOnUiThread {

            statusText.text =
                text

            statusText.setTextColor(
                getColor(color)
            )
        }
    }

    private fun writeLog(message: String) {

        try {

            val line =
                "${logFormat.format(Date())} | $message\n"

            synchronized(logFile) {

                /*
                 * Manteniamo il file piccolo.
                 * Se supera 500 KB lo ricominciamo.
                 */
                if (
                    logFile.exists() &&
                    logFile.length() > 500_000
                ) {
                    logFile.writeText("")
                }

                logFile.appendText(line)
            }

        } catch (_: Exception) {
            // Il logging non deve mai bloccare il monitoraggio.
        }
    }
}
