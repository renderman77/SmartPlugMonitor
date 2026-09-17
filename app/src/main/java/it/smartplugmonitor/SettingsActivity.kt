package it.smartplugmonitor

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val ipEditText = findViewById<EditText>(R.id.ipEditText)
        val deviceIdEditText = findViewById<EditText>(R.id.deviceIdEditText)
        val localKeyEditText = findViewById<EditText>(R.id.localKeyEditText)
        val thresholdEditText = findViewById<EditText>(R.id.offThresholdEditText)
        val debounceEditText = findViewById<EditText>(R.id.debounceEditText)
        val alertTitleEditText = findViewById<EditText>(R.id.alertTitleEditText)
        val alertMessageEditText = findViewById<EditText>(R.id.alertMessageEditText)
        val saveButton = findViewById<Button>(R.id.saveButton)

        val preferences = getSharedPreferences("settings", MODE_PRIVATE)

        ipEditText.setText(preferences.getString("ip_address", ""))
        deviceIdEditText.setText(preferences.getString("device_id", ""))
        localKeyEditText.setText(preferences.getString("local_key", ""))
        thresholdEditText.setText(preferences.getString("off_threshold", "10"))
        debounceEditText.setText(preferences.getString("debounce_seconds", "90"))
        alertTitleEditText.setText(preferences.getString("alert_title", "Ciclo terminato"))
        alertMessageEditText.setText(preferences.getString("alert_message", "Il dispositivo ha terminato."))

        saveButton.setOnClickListener {
            preferences.edit()
                .putString("ip_address", ipEditText.text.toString().trim())
                .putString("device_id", deviceIdEditText.text.toString().trim())
                .putString("local_key", localKeyEditText.text.toString().trim())
                .putString("off_threshold", thresholdEditText.text.toString().trim())
                .putString("debounce_seconds", debounceEditText.text.toString().trim())
                .putString("alert_title", alertTitleEditText.text.toString().trim())
                .putString("alert_message", alertMessageEditText.text.toString().trim())
                .apply()

            finish()
        }
    }
}
