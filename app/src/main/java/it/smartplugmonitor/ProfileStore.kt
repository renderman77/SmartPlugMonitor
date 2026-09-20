package it.smartplugmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un profilo = un elettrodomestico da monitorare con questa presa
 * (es. "Lavatrice", "Caricabatterie Makita"), con la sua soglia e
 * durata, indipendenti dagli altri profili.
 */
data class AppProfile(
    var id: String,
    var name: String,
    var offThreshold: Double,
    var debounceSeconds: Long,
    var calibrationEnabled: Boolean,
    var maxPauseSeconds: Long? // null = nessuna pausa ancora registrata
)

/**
 * Salva/carica l'elenco dei profili e quale sia quello attivo, usando
 * le stesse SharedPreferences già in uso per le altre impostazioni.
 * Non tocca in nessun modo la connessione alla presa (IP/Local Key
 * restano impostazioni globali, separate, in un'unica presa).
 */
object ProfileStore {

    private const val PREFS_NAME = "settings"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_ACTIVE_ID = "active_profile_id"

    fun loadProfiles(context: Context): MutableList<AppProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)

        if (raw.isNullOrEmpty()) {
            // Primo avvio dopo l'aggiornamento: creiamo un profilo di
            // default, recuperando le vecchie impostazioni singole se
            // c'erano già (così chi aveva già calibrato soglia/durata
            // non perde nulla).
            val oldThreshold = prefs.getString("off_threshold", "10")?.toDoubleOrNull() ?: 10.0
            val oldDebounce = prefs.getString("debounce_seconds", "10")?.toLongOrNull() ?: 10L

            val default = AppProfile(
                id = "default",
                name = "Appliance 1",
                offThreshold = oldThreshold,
                debounceSeconds = oldDebounce,
                calibrationEnabled = false,
                maxPauseSeconds = null
            )

            val list = mutableListOf(default)
            saveProfiles(context, list)
            setActiveProfileId(context, default.id)
            return list
        }

        val array = JSONArray(raw)
        val list = mutableListOf<AppProfile>()

        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list.add(
                AppProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    offThreshold = o.getDouble("offThreshold"),
                    debounceSeconds = o.getLong("debounceSeconds"),
                    calibrationEnabled = o.optBoolean("calibrationEnabled", false),
                    maxPauseSeconds = if (o.has("maxPauseSeconds") && !o.isNull("maxPauseSeconds"))
                        o.getLong("maxPauseSeconds") else null
                )
            )
        }
        return list
    }

    fun saveProfiles(context: Context, profiles: List<AppProfile>) {
        val array = JSONArray()
        for (p in profiles) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("offThreshold", p.offThreshold)
            o.put("debounceSeconds", p.debounceSeconds)
            o.put("calibrationEnabled", p.calibrationEnabled)
            if (p.maxPauseSeconds != null) o.put("maxPauseSeconds", p.maxPauseSeconds) else o.put("maxPauseSeconds", JSONObject.NULL)
            array.put(o)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, array.toString())
            .apply()
    }

    fun getActiveProfileId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_ID, null)
    }

    fun setActiveProfileId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_ID, id)
            .apply()
    }

    fun getActiveProfile(context: Context): AppProfile {
        val profiles = loadProfiles(context)
        val activeId = getActiveProfileId(context)
        return profiles.find { it.id == activeId } ?: profiles.first()
    }

    fun newProfileId(): String = "profile_" + System.currentTimeMillis()
}
