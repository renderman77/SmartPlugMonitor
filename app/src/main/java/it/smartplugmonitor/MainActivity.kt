package it.smartplugmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var powerText: TextView
    private lateinit var connectionText: TextView
    private lateinit var toggleButton: Button
    private lateinit var profileSpinner: Spinner

    private var profilesInSpinner: List<AppProfile> = emptyList()
    private var suppressSpinnerCallback = false

    private val uiHandler = Handler(Looper.getMainLooper())

    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            refreshUiFromService()
            uiHandler.postDelayed(this, 5000L)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
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
        refreshProfileSpinner()
        updateToggleButtonLabel()
        uiHandler.post(uiRefreshRunnable)
    }

    /** Ricarica l'elenco profili (potrebbero essere cambiati nelle
     *  Impostazioni) e seleziona quello attivo, senza far scattare il
     *  listener di cambio selezione durante il refresh. Lo spinner
     *  viene disabilitato mentre il monitoraggio è in corso, per non
     *  cambiare profilo a metà sessione. */
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

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRefreshRunnable)
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun stopMonitorService() {
        stopService(Intent(this, MonitorService::class.java))
    }

    private fun updateToggleButtonLabel() {
        if (MonitorService.isServiceRunning) {
            toggleButton.text = "STOP"
            toggleButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F")) // Rosso scuro
        } else {
            toggleButton.text = "START"
            toggleButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C")) // Verde scuro
        }
    }

    private fun refreshUiFromService() {
        updateToggleButtonLabel()
        profileSpinner.isEnabled = !MonitorService.isServiceRunning
        powerText.text = MonitorService.lastPowerText
        statusText.text = "\u25CF  " + MonitorService.lastStatusText.uppercase()
        connectionText.text = MonitorService.lastConnectionText

        val colorRes = when {
            !MonitorService.isServiceRunning -> android.R.color.darker_gray
            MonitorService.lastStatusText.equals("Running", ignoreCase = true) ->
                android.R.color.holo_green_dark
            MonitorService.lastConnectionText.contains("error", ignoreCase = true) ||
                MonitorService.lastConnectionText.contains("not", ignoreCase = true) ->
                android.R.color.holo_red_dark
            else -> android.R.color.holo_blue_dark
        }
        statusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun maybeAskIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
        }
    }
}
