package it.smartplugmonitor

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private val MAX_PROFILES = 5

    private lateinit var profilesContainer: LinearLayout
    private lateinit var addProfileButton: Button

    // Uno per ogni riga profilo attualmente mostrata a schermo, nello
    // stesso ordine delle view dentro profilesContainer.
    private val profiles = mutableListOf<AppProfile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val ipEditText = findViewById<EditText>(R.id.ipEditText)
        val localKeyEditText = findViewById<EditText>(R.id.localKeyEditText)
        val normalIntervalEditText = findViewById<EditText>(R.id.normalIntervalEditText)
        val alertIntervalEditText = findViewById<EditText>(R.id.alertIntervalEditText)
        val saveButton = findViewById<Button>(R.id.saveButton)
        profilesContainer = findViewById(R.id.profilesContainer)
        addProfileButton = findViewById(R.id.addProfileButton)

        val preferences = getSharedPreferences("settings", MODE_PRIVATE)

        ipEditText.setText(preferences.getString("ip_address", ""))
        localKeyEditText.setText(preferences.getString("local_key", ""))
        normalIntervalEditText.setText(
            preferences.getString("normal_interval_seconds", "20")
        )
        alertIntervalEditText.setText(
            preferences.getString("alert_interval_seconds", "5")
        )

        profiles.addAll(ProfileStore.loadProfiles(this))
        profiles.forEach { addProfileRow(it) }
        updateAddButtonState()

        addProfileButton.setOnClickListener {
            if (profiles.size >= MAX_PROFILES) {
                Toast.makeText(applicationContext, "Max $MAX_PROFILES profiles", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val newProfile = AppProfile(
                id = ProfileStore.newProfileId(),
                name = "",
                offThreshold = 10.0,
                debounceSeconds = 10L,
                calibrationEnabled = false,
                maxPauseSeconds = null
            )
            profiles.add(newProfile)
            addProfileRow(newProfile)
            updateAddButtonState()
        }

        saveButton.setOnClickListener {

            val normalInterval = normalIntervalEditText.text.toString().trim().toIntOrNull() ?: 20
            if (normalInterval < 10) {
                Toast.makeText(applicationContext, "Minimum 10 seconds recommended", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val alertInterval = alertIntervalEditText.text.toString().trim().toIntOrNull() ?: 5
            if (alertInterval < 2) {
                Toast.makeText(applicationContext, "Minimum 2 seconds recommended", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            preferences.edit()
                .putString("ip_address", ipEditText.text.toString().trim())
                .putString("local_key", localKeyEditText.text.toString().trim())
                .putString("normal_interval_seconds", normalInterval.toString())
                .putString("alert_interval_seconds", alertInterval.toString())
                .apply()

            val updated = collectProfilesFromRows()

            if (updated.isEmpty()) {
                Toast.makeText(applicationContext, "Keep at least one profile", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (updated.any { it.name.isBlank() }) {
                Toast.makeText(applicationContext, "Every profile needs a name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            ProfileStore.saveProfiles(this, updated)

            // Se il profilo attivo è stato eliminato, ne scegliamo uno
            // valido di default (il primo rimasto).
            val activeId = ProfileStore.getActiveProfileId(this)
            if (updated.none { it.id == activeId }) {
                ProfileStore.setActiveProfileId(this, updated.first().id)
            }

            finish()
        }
    }

    /** Aggiunge a schermo una riga per un profilo, precompilata. */
    private fun addProfileRow(profile: AppProfile) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_profile, profilesContainer, false)

        row.findViewById<EditText>(R.id.profileNameEditText).setText(profile.name)
        row.findViewById<EditText>(R.id.profileThresholdEditText)
            .setText(formatThreshold(profile.offThreshold))
        row.findViewById<EditText>(R.id.profileDurationEditText)
            .setText(profile.debounceSeconds.toString())
        row.findViewById<CheckBox>(R.id.profileCalibrationCheckBox).isChecked = profile.calibrationEnabled

        val maxPauseText = row.findViewById<TextView>(R.id.profileMaxPauseText)
        if (profile.maxPauseSeconds != null) {
            maxPauseText.text = "Max pause seen: ${profile.maxPauseSeconds}s (safety margin included)"
            maxPauseText.visibility = View.VISIBLE
        } else {
            maxPauseText.visibility = View.GONE
        }

        row.findViewById<Button>(R.id.profileDeleteButton).setOnClickListener {
            if (profiles.size <= 1) {
                Toast.makeText(applicationContext, "Keep at least one profile", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val index = profilesContainer.indexOfChild(row)
            if (index >= 0 && index < profiles.size) {
                profiles.removeAt(index)
            }
            profilesContainer.removeView(row)
            updateAddButtonState()
        }

        // Tag per ritrovare l'id del profilo associato a questa riga
        // quando si salva, indipendentemente dall'ordine.
        row.tag = profile.id

        profilesContainer.addView(row)
    }

    private fun updateAddButtonState() {
        addProfileButton.isEnabled = profiles.size < MAX_PROFILES
    }

    private fun formatThreshold(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /** Legge i valori attuali dalle righe a schermo, nell'ordine in cui
     *  compaiono, riassociandoli agli id dei profili originali. */
    private fun collectProfilesFromRows(): List<AppProfile> {
        val result = mutableListOf<AppProfile>()

        for (i in 0 until profilesContainer.childCount) {
            val row = profilesContainer.getChildAt(i)
            val id = row.tag as? String ?: ProfileStore.newProfileId()

            val name = row.findViewById<EditText>(R.id.profileNameEditText).text.toString().trim()
            val threshold = row.findViewById<EditText>(R.id.profileThresholdEditText)
                .text.toString().trim().toDoubleOrNull() ?: 10.0
            val duration = row.findViewById<EditText>(R.id.profileDurationEditText)
                .text.toString().trim().toLongOrNull() ?: 10L
            val calibrationEnabled =
                row.findViewById<CheckBox>(R.id.profileCalibrationCheckBox).isChecked

            // Manteniamo il valore di calibrazione già salvato per
            // quel profilo (non lo si edita a mano da qui).
            val existingMaxPause = profiles.find { it.id == id }?.maxPauseSeconds

            // Se l'utente ha appena disattivato la calibrazione,
            // ripartiamo puliti: il prossimo utilizzo misurerà di
            // nuovo da zero. Se resta attiva, teniamo il valore.
            val maxPause = if (calibrationEnabled) existingMaxPause else null

            result.add(
                AppProfile(
                    id = id,
                    name = name,
                    offThreshold = threshold,
                    debounceSeconds = duration,
                    calibrationEnabled = calibrationEnabled,
                    maxPauseSeconds = maxPause
                )
            )
        }

        return result
    }
}
