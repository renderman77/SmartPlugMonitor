package it.smartplugmonitor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvPower: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnection: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSettings: Button

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            tvPower.text = MonitorService.lastPowerText
            tvStatus.text = MonitorService.lastStatusText
            tvConnection.text = MonitorService.lastConnectionText

            if (MonitorService.isServiceRunning) {
                btnToggle.text = "Stop Monitor"
            } else {
                btnToggle.text = "Start Monitor"
            }

            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPower = findViewById(R.id.tv_power)
        tvStatus = findViewById(R.id.tv_status)
        tvConnection = findViewById(R.id.tv_connection)
        btnToggle = findViewById(R.id.btn_toggle)
        btnSettings = findViewById(R.id.btn_settings)

        btnToggle.setOnClickListener {
            if (MonitorService.isServiceRunning) {
                val intent = Intent(this, MonitorService::class.java)
                stopService(intent)
            } else {
                val intent = Intent(this, MonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        checkAndRequestBatteryOptimizations()
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun checkAndRequestBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            
            if (!isIgnoring) {
                AlertDialog.Builder(this)
                    .setTitle("Consumo in Background")
                    .setMessage("Per ricevere gli avvisi della lavatrice in tempo reale ed evitare ritardi a schermo spento, è necessario impostare la batteria dell'app su 'Nessuna restrizione'.")
                    .setPositiveButton("Imposta Ora") { _, _ ->
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    }
                    .setNegativeButton("Più Tardi", null)
                    .setCancelable(false)
                    .show()
            }
        }
    }
}
