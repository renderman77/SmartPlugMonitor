package it.smartplugmonitor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Activity Android "pura" (non AppCompatActivity): la nostra UI usa
 * solo componenti di base (EditText, Button, Spinner, CheckBox,
 * TextView), quindi non serve la libreria di compatibilità Material —
 * ed è proprio quella libreria (nello specifico l'inflater che applica
 * lo stile ai TextView) ad aver causato il crash del Toast su alcuni
 * telefoni Android 8. Con un'Activity semplice, quella categoria intera
 * di bug non può più presentarsi.
 */
class MainActivity : Activity() {

    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

    private lateinit var statusText: TextView
    private lateinit var powerText: TextView
    private lateinit var connectionText: TextView
    private lateinit var toggleButton: Button
    private lateinit var profileSpinner: Spinner

    private var profilesInSpinner: List<AppProfile> = emptyList()
    private var suppressSpinnerCallback = false

    private val uiHandler = Handler(Looper.getMainLooper())

    private val uiRefreshRunnable =
        object : Runnable {
            override fun run() {
                refreshUiFromService()
                uiHandler.postDelayed(this, 5000L)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        powerText = findViewById(R.id.powerText)
        connectionText = findViewById(R.id.connectionText)
        toggleButton = findViewById(R.id.toggleButton)
        profileSpinner = findViewById(R.id.profileSpinner)

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinnerCallback) return
                if (position in profilesInSpinner.indices) {
                    ProfileStore.setActiveProfileId(this@MainActivity, profilesInSpinner[position].id)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

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
            updateToggleButton()
        }

        requestNotificationPermissionIfNeeded()
        maybeAskIgnoreBatteryOptimizations()
    }

    override fun onResume() {
        super.onResume()
        refreshProfileSpinner()
        updateToggleButton()
        uiHandler.post(uiRefreshRunnable)

        // Segnala al servizio (se attivo) che siamo in primo piano:
        // può passare alla modalità più reattiva per la durata in cui
        // guardiamo lo schermo.
        if (MonitorService.isServiceRunning) {
            sendForegroundSignal(true)
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRefreshRunnable)

        if (MonitorService.isServiceRunning) {
            sendForegroundSignal(false)
        }
    }

    private fun sendForegroundSignal(foreground: Boolean) {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = if (foreground) "APP_FOREGROUND" else "APP_BACKGROUND"
        }
        startService(intent)
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        // L'app è in primo piano proprio ora: segnaliamolo subito,
        // l'onStartCommand del servizio gestisce anche questo caso.
        uiHandler.postDelayed({ sendForegroundSignal(true) }, 300L)
    }

    private fun stopMonitorService() {
        stopService(Intent(this, MonitorService::class.java))
    }

    private fun updateToggleButton() {
        if (MonitorService.isServiceRunning) {
            toggleButton.text = "STOP"
            toggleButton.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            toggleButton.text = "START"
            toggleButton.setBackgroundColor(Color.parseColor("#2E7D32"))
        }
        toggleButton.setTextColor(Color.WHITE)
    }

    private fun refreshProfileSpinner() {
        profilesInSpinner = ProfileStore.loadProfiles(this)
        val names = profilesInSpinner.map { it.name.ifBlank { "(unnamed)" } }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        suppressSpinnerCallback = true
        profileSpinner.adapter = adapter

        val activeId = ProfileStore.getActiveProfileId(this)
        val activeIndex = profilesInSpinner.indexOfFirst { it.id == activeId }
        if (activeIndex >= 0) profileSpinner.setSelection(activeIndex)
        suppressSpinnerCallback = false

        profileSpinner.isEnabled = !MonitorService.isServiceRunning
    }

    private fun refreshUiFromService() {
        updateToggleButton()
        profileSpinner.isEnabled = !MonitorService.isServiceRunning
        powerText.text = MonitorService.lastPowerText
        statusText.text = "\u25CF  " + MonitorService.lastStatusText.uppercase()
        connectionText.text = MonitorService.lastConnectionText

        val color =
            when {
                !MonitorService.isServiceRunning -> android.R.color.darker_gray
                MonitorService.lastStatusText == "Running" -> android.R.color.holo_green_dark
                MonitorService.lastStatusText == "Cycle finished" -> android.R.color.holo_orange_dark
                MonitorService.lastConnectionText.contains("error", ignoreCase = true) -> android.R.color.holo_red_dark
                else -> android.R.color.holo_blue_dark
            }
        statusText.setTextColor(getColor(color))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED

            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Se negato, semplicemente non arriveranno notifiche — nessuna
        // azione necessaria qui.
    }

    private fun maybeAskIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Alcuni produttori bloccano questo intent di sistema:
                // in quel caso va disattivato manualmente dalle
                // impostazioni del telefono.
            }
        }
    }
}
