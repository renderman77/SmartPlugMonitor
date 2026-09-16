package it.smartplugmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var powerText: TextView
    private lateinit var connectionText: TextView

    private val monitorReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {
            if (intent?.action != MonitorService.ACTION_UPDATE) {
                return
            }

            val power =
                intent.getDoubleExtra(
                    MonitorService.EXTRA_POWER,
                    0.0
                )

            val state =
                intent.getStringExtra(
                    MonitorService.EXTRA_STATE
                ) ?: "MONITORAGGIO ATTIVO"

            val connection =
                intent.getStringExtra(
                    MonitorService.EXTRA_CONNECTION
                ) ?: ""

            powerText.text =
                String.format(
                    java.util.Locale.US,
                    "%.1f W",
                    power
                )

            statusText.text = "●  $state"

            statusText.setTextColor(
                when (state) {
                    "IN FUNZIONE" ->
                        getColor(
                            android.R.color.holo_green_dark
                        )

                    "CICLO TERMINATO" ->
                        getColor(
                            android.R.color.holo_orange_dark
                        )

                    "PRESA NON RAGGIUNGIBILE" ->
                        getColor(
                            android.R.color.holo_red_dark
                        )

                    else ->
                        getColor(
                            android.R.color.holo_green_dark
                        )
                }
            )

            connectionText.text = connection
        }
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        statusText =
            findViewById(R.id.statusText)

        powerText =
            findViewById(R.id.powerText)

        connectionText =
            findViewById(R.id.connectionText)

        findViewById<ImageButton>(
            R.id.settingsButton
        ).setOnClickListener {

            startActivity(
                Intent(
                    this,
                    SettingsActivity::class.java
                )
            )
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                100
            )
        }
    }

    override fun onStart() {
        super.onStart()

        ContextCompat.registerReceiver(
            this,
            monitorReceiver,
            IntentFilter(
                MonitorService.ACTION_UPDATE
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val intent =
            Intent(
                this,
                MonitorService::class.java
            )

        ContextCompat.startForegroundService(
            this,
            intent
        )
    }

    override fun onStop() {
        super.onStop()

        unregisterReceiver(
            monitorReceiver
        )
    }
}
