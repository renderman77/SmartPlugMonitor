package it.smartplugmonitor

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()

        val connectionText = findViewById<android.widget.TextView>(R.id.connectionText)
        val statusText = findViewById<android.widget.TextView>(R.id.statusText)

        val preferences = getSharedPreferences("settings", MODE_PRIVATE)

        val ip = preferences.getString("ip_address", "") ?: ""
        val deviceId = preferences.getString("device_id", "") ?: ""
        val localKey = preferences.getString("local_key", "") ?: ""

        if (ip.isEmpty() || deviceId.isEmpty() || localKey.isEmpty()) {
            statusText.text = "●  NON CONFIGURATO"
            statusText.setTextColor(
                getColor(android.R.color.darker_gray)
            )

            connectionText.text = "Configura la presa nelle impostazioni"
        } else {
            statusText.text = "●  MONITORAGGIO ATTIVO"
            statusText.setTextColor(
                getColor(android.R.color.holo_green_dark)
            )

            connectionText.text = "Presa configurata"
        }
    }
}
