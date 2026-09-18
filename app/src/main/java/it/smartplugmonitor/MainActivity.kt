package it.smartplugmonitor

import android.Manifest
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

    // Must be registered as a class property (not inside onCreate),
    // this is an Android requirement for this kind of request.
    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* if denied, notifications simply won't arrive */ }

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

            updateToggleButton()
        }

        requestNotificationPermissionIfNeeded()
        maybeAskIgnoreBatteryOptimizations()
    }

    override fun onResume() {
        super.onResume()
        updateToggleButton()
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

    private fun refreshUiFromService() {

        // Also updated here (not just on click and in onResume):
        // starting/stopping the service isn't instant, so the button
        // needs to self-correct within a second instead of getting
        // stuck on the wrong label/color.
        updateToggleButton()

        powerText.text = MonitorService.lastPowerText
        statusText.text = "\u25CF  " + MonitorService.lastStatusText.uppercase()
        connectionText.text = MonitorService.lastConnectionText

        val color =
            when {
                !MonitorService.isServiceRunning ->
                    android.R.color.darker_gray

                MonitorService.lastStatusText == "Running" ->
                    android.R.color.holo_green_dark

                MonitorService.lastStatusText == "Cycle finished" ->
                    android.R.color.holo_orange_dark

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
                // Some manufacturers (certain Samsung/Xiaomi builds)
                // block this system intent: in that case, battery
                // saving for the app must be disabled manually from
                // the phone's own settings.
            }
        }
    }
}
