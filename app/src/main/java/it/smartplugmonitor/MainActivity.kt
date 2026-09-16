package it.smartplugmonitor

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var client: TuyaClient? = null
    private var monitorThread: Thread? = null

    private lateinit var statusText: TextView
    private lateinit var powerText: TextView
    private lateinit var connectionText: TextView

    @Volatile
    private var running = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        powerText = findViewById(R.id.powerText)
        connectionText = findViewById(R.id.connectionText)

        findViewById<ImageButton>(R.id.settingsButton)
            .setOnClickListener {
                startActivity(
                    android.content.Intent(
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
            getSharedPreferences("settings", MODE_PRIVATE)

        val ip =
            preferences.getString("ip_address", "") ?: ""

        val deviceId =
            preferences.getString("device_id", "") ?: ""

        val localKey =
            preferences.getString("local_key", "") ?: ""

        if (
            ip.isEmpty() ||
            deviceId.isEmpty() ||
            localKey.isEmpty()
        ) {
            statusText.text = "●  NON CONFIGURATO"

            statusText.setTextColor(
                getColor(
                    android.R.color.darker_gray
                )
            )

            connectionText.text =
                "Configura la presa nelle impostazioni"

            return
        }

        val pollInterval =
            (
                preferences.getString(
                    "poll_interval",
                    "5"
                ) ?: "5"
            )
                .toLongOrNull()
                ?.coerceAtLeast(1)
                ?: 5

        client =
            TuyaClient(
                deviceId,
                ip,
                localKey
            )

        monitorThread =
            Thread {

                while (running) {

                    try {

                        val power =
                            client!!.getPower()

                        runOnUiThread {

                            powerText.text =
                                String.format(
                                    java.util.Locale.US,
                                    "%.1f W",
                                    power
                                )

                            statusText.text =
                                "●  MONITORAGGIO ATTIVO"

                            statusText.setTextColor(
                                getColor(
                                    android.R.color.holo_green_dark
                                )
                            )

                            connectionText.text =
                                "Presa collegata"
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

                    } catch (
                        _: InterruptedException
                    ) {

                        break
                    }
                }

            }.also {
                it.start()
            }
    }
}
