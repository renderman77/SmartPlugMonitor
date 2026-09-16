package it.smartplugmonitor

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)

        val ipEditText =
            findViewById<EditText>(R.id.ipEditText)

        val deviceIdEditText =
            findViewById<EditText>(R.id.deviceIdEditText)

        val localKeyEditText =
            findViewById<EditText>(R.id.localKeyEditText)

        val thresholdEditText =
            findViewById<EditText>(R.id.offThresholdEditText)

        val debounceEditText =
            findViewById<EditText>(R.id.debounceEditText)

        val pollIntervalEditText =
            findViewById<EditText>(R.id.pollIntervalEditText)

        val notificationStyleGroup =
            findViewById<android.widget.RadioGroup>(R.id.notificationStyleGroup)

        val saveButton =
            findViewById<Button>(R.id.saveButton)

        val preferences =
            getSharedPreferences(
                "settings",
                MODE_PRIVATE
            )

        ipEditText.setText(
            preferences.getString(
                "ip_address",
                ""
            )
        )

        deviceIdEditText.setText(
            preferences.getString(
                "device_id",
                ""
            )
        )

        localKeyEditText.setText(
            preferences.getString(
                "local_key",
                ""
            )
        )

        /*
         * Una sola soglia.
         * Se esiste ancora il vecchio valore off_threshold,
         * lo utilizziamo per non perdere l'impostazione già salvata.
         * Altrimenti il default è 10 W.
         */
        thresholdEditText.setText(
            preferences.getString(
                "off_threshold",
                "10"
            )
        )

        debounceEditText.setText(
            preferences.getString(
                "debounce_seconds",
                "90"
            )
        )

        pollIntervalEditText.setText(
            preferences.getString(
                "poll_interval",
                "5"
            )
        )

        when (
            preferences.getString(
                "notification_style",
                "allarme"
            )
        ) {
            "normale" -> notificationStyleGroup.check(R.id.styleNormale)
            "vibrazione" -> notificationStyleGroup.check(R.id.styleVibrazione)
            else -> notificationStyleGroup.check(R.id.styleAllarme)
        }

        saveButton.setOnClickListener {

            val threshold =
                thresholdEditText.text
                    .toString()
                    .trim()

            val debounce =
                debounceEditText.text
                    .toString()
                    .trim()

            val pollInterval =
                pollIntervalEditText.text
                    .toString()
                    .trim()

            val notificationStyle =
                when (notificationStyleGroup.checkedRadioButtonId) {
                    R.id.styleNormale -> "normale"
                    R.id.styleVibrazione -> "vibrazione"
                    else -> "allarme"
                }

            preferences.edit()
                .putString(
                    "ip_address",
                    ipEditText.text
                        .toString()
                        .trim()
                )
                .putString(
                    "device_id",
                    deviceIdEditText.text
                        .toString()
                        .trim()
                )
                .putString(
                    "local_key",
                    localKeyEditText.text
                        .toString()
                        .trim()
                )
                .putString(
                    "off_threshold",
                    threshold
                )
                .putString(
                    "debounce_seconds",
                    debounce
                )
                .putString(
                    "poll_interval",
                    pollInterval
                )
                .putString(
                    "notification_style",
                    notificationStyle
                )
                .apply()

            finish()
        }
    }
}
