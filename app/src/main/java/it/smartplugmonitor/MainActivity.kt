package it.smartplugmonitor

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
        CONTEGGIO_FINE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        powerText = findViewById(R.id.powerText)
        connectionText = findViewById(R.id.connectionText)

        findViewById<ImageButton>(R.id.settingsButton)
            .setOnClickListener {

                startActivity(
                    Intent(
                        this,
                        SettingsActivity::class.java
                    )
                )
            }
    }

    override fun onResume() {
        super.onResume()

        running = true
        startMonitoring()
    }

    override fun onPause() {
        super.onPause()

        running = false

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

        if (ip.isEmpty() ||
            deviceId.isEmpty() ||
            localKey.isEmpty()
        ) {

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

        client =
            TuyaClient(
                deviceId,
                ip,
                localKey
            )

        monitorThread = Thread {

            var state = State.ATTESA

            var lowPowerStart: Long? = null

            while (running) {

                try {

                    val power =
                        client?.getPower()
                            ?: throw Exception(
                                "Connessione assente"
                            )

                    val now =
                        System.currentTimeMillis()

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

                    when (state) {

                        State.ATTESA -> {

                            lowPowerStart = null

                            if (power >= threshold) {

                                state =
                                    State.IN_FUNZIONE

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

                                /*
                                 * Il ciclo è ancora attivo.
                                 * Qualsiasi vecchio conteggio
                                 * viene cancellato.
                                 */
                                lowPowerStart = null

                                updateStatus(
                                    "●  IN FUNZIONE",
                                    android.R.color.holo_green_dark
                                )

                            } else {

                                /*
                                 * Sotto soglia:
                                 * parte un NUOVO conteggio.
                                 */
                                lowPowerStart = now

                                state =
                                    State.CONTEGGIO_FINE

                                updateStatus(
                                    "●  POSSIBILE FINE CICLO",
                                    android.R.color.holo_orange_dark
                                )
                            }
                        }

                        State.CONTEGGIO_FINE -> {

                            if (power >= threshold) {

                                /*
                                 * È tornata sopra soglia.
                                 *
                                 * Quindi era una pausa o comunque
                                 * il ciclo non era terminato.
                                 *
                                 * CANCELLIAMO COMPLETAMENTE
                                 * il conteggio.
                                 */
                                lowPowerStart = null

                                state =
                                    State.IN_FUNZIONE

                                updateStatus(
                                    "●  IN FUNZIONE",
                                    android.R.color.holo_green_dark
                                )

                            } else {

                                /*
                                 * È ancora sotto soglia.
                                 * Il conteggio continua.
                                 */
                                val start =
                                    lowPowerStart ?: now

                                val elapsed =
                                    (now - start) / 1000L

                                if (elapsed >= debounceSeconds) {

                                    /*
                                     * FINE CICLO.
                                     *
                                     * La notifica verrà inserita
                                     * qui nel prossimo passaggio.
                                     */
                                    updateStatus(
                                        "●  CICLO TERMINATO",
                                        android.R.color.holo_blue_dark
                                    )

                                    state =
                                        State.ATTESA

                                    lowPowerStart =
                                        null

                                } else {

                                    updateStatus(
                                        "●  POSSIBILE FINE CICLO",
                                        android.R.color.holo_orange_dark
                                    )
                                }
                            }
                        }
                    }

                } catch (e: Exception) {

                    client?.close()

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

            statusText.text = text

            statusText.setTextColor(
                getColor(color)
            )
        }
    }
}
