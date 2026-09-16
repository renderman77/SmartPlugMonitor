package it.smartplugmonitor

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var powerText: TextView
    private lateinit var connectionText: TextView

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

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

        startMonitorService()
    }

    override fun onResume() {
        super.onResume()

        startMonitorService()
    }

    private fun startMonitorService() {

        val intent =
            Intent(
                this,
                MonitorService::class.java
            )

        if (
            android.os.Build.VERSION.SDK_INT >= 26
        ) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
