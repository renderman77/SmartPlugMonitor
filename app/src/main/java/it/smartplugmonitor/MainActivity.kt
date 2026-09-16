package it.smartplugmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var powerText: TextView
    private lateinit var connectionText: TextView
    private lateinit var toggleButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())

    private val uiRefreshRunnable =
        object : Runnable {
            override fun run() {
                refreshUiFromService()
                uiHandler.postDelayed(this, 1000L)
            }
        }

    // Deve essere registrato come proprietà della classe (non dentro
    // onCreate), è un requisito di Android per questo tipo di richiesta.
    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* se l'utente nega, semplicemente non arriveranno notifiche */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        powerText = findViewById(R.id.powerText)
        connectionText = findViewById(R.id.connectionText)
        toggleButton = findViewById(R.id.toggleButton)

        findViewById<ImageButton>(R.id.settingsButton)
            .setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }

        toggleButton.setOnClickListener {

            if (MonitorService.isServiceRunning) {
                stopMonitorService()
            } else {
                requestNotificationPermissionIfNeeded()
                startMonitorService()
            }

            updateToggleButtonLabel()
        }

        requestNotificationPermissionIfNeeded()
        maybeAskIgnoreBatteryOptimizations()
    }

    override fun onResume() {
        super.onResume()
        updateToggleButtonLabel()
        uiHandler.post(uiRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRefreshRunnable)
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMonitorService() {
        stopService(Intent(this, MonitorService::class.java))
    }

    private fun updateToggleButtonLabel() {

        toggleButton.text =
            if (MonitorService.isServiceRunning) {
                "Ferma monitoraggio"
            } else {
                "Avvia monitoraggio"
            }
    }

    private fun refreshUiFromService() {

        // Aggiornato anche qui (non solo al click e in onResume):
        // avvio/arresto del servizio non sono istantanei, quindi il
        // pulsante deve potersi "autocorreggere" da solo entro un
        // secondo invece di restare bloccato sulla scritta sbagliata.
        updateToggleButtonLabel()

        powerText.text = MonitorService.lastPowerText
        statusText.text = "\u25CF  " + MonitorService.lastStatusText.uppercase()
        connectionText.text = MonitorService.lastConnectionText

        val color =
            when {
                !MonitorService.isServiceRunning ->
                    android.R.color.darker_gray

                MonitorService.lastStatusText == "In funzione" ->
                    android.R.color.holo_green_dark

                MonitorService.lastConnectionText.contains(
                    "non raggiungibile",
                    ignoreCase = true
                ) -> android.R.color.holo_red_dark

                else -> android.R.color.holo_blue_dark
            }

        statusText.setTextColor(getColor(color))
    }

    private fun requestNotificationPermissionIfNeeded() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            val granted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun maybeAskIgnoreBatteryOptimizations() {

        val powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {

            try {

                val intent =
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply {
                        data = Uri.parse("package:$packageName")
                    }

                startActivity(intent)

            } catch (_: Exception) {
                // Alcuni produttori (es. certi Samsung/Xiaomi) bloccano
                // questo intent di sistema: in quel caso va disattivato
                // manualmente il risparmio energetico per l'app dalle
                // impostazioni del telefono.
            }
        }
    }
}
