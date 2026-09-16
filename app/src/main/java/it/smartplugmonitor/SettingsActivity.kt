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

        val onThresholdEditText =
            findViewById<EditText>(R.id.onThresholdEditText)

        val offThresholdEditText =
            findViewById<EditText>(R.id.offThresholdEditText)

        val debounceEditText =
            findViewById<EditText>(R.id.debounceEditText)

        val pollIntervalEditText =
            findViewById<EditText>(R.id.pollIntervalEditText)

        val saveButton = findViewById<Button>(R.id.saveButton)

        val preferences = getSharedPreferences("settings", MODE_PRIVATE)

        ipEditText.setText(
            preferences.getString("ip_address", "")
        )

        deviceIdEditText.setText(
            preferences.getString("device_id", "")
        )

        localKeyEditText.setText(
            preferences.getString("local_key", "")
        )

        onThresholdEditText.setText(
            preferences.getString("on_threshold", "50")
        )

        offThresholdEditText.setText(
            preferences.getString("off_threshold", "8")
        )

        debounceEditText.setText(
            preferences.getString("debounce_seconds", "90")
        )

        pollIntervalEditText.setText(
            preferences.getString("poll_interval", "5")
        )

        saveButton.setOnClickListener {

            preferences.edit()
                .putString("ip_address", ipEditText.text.toString().trim())
                .putString("device_id", deviceIdEditText.text.toString().trim())
                .putString("local_key", localKeyEditText.text.toString().trim())
                .putString("on_threshold", onThresholdEditText.text.toString().trim())
                .putString("off_threshold", offThresholdEditText.text.toString().trim())
                .putString("debounce_seconds", debounceEditText.text.toString().trim())
                .putString("poll_interval", pollIntervalEditText.text.toString().trim())
                .apply()

            finish()
        }
    }
}
