package com.eara.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.gestureDataStore by preferencesDataStore(name = "eara_gesture_prefs")

/**
 * Local, on-device only storage for the user's tap/hold -> action mapping.
 * Nothing here is uploaded anywhere.
 */
class GestureConfigStore(private val context: Context) {

    companion object {
        private val KEY_CONFIG = stringPreferencesKey("gesture_config_json")

        // Mirrors the defaults already present in the existing UI (index.html)
        val DEFAULT_CONFIG = mapOf(
            "1tap" to "NEXT REEL",
            "2tap" to "LIKE",
            "3tap" to "PREVIOUS REEL",
            "hold" to "PAUSE",
            "4tap" to "CUSTOM ACTION"
        )
    }

    val configFlow: Flow<Map<String, String>> = context.gestureDataStore.data.map { prefs ->
        parse(prefs[KEY_CONFIG])
    }

    suspend fun getConfig(): Map<String, String> {
        return configFlow.first()
    }

    suspend fun saveConfigJson(json: String) {
        // Validate before persisting; never save garbage that would break the engine.
        val parsed = parse(json)
        context.gestureDataStore.edit { it[KEY_CONFIG] = JSONObject(parsed as Map<*, *>).toString() }
    }

    private fun parse(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return DEFAULT_CONFIG
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k -> map[k] = obj.getString(k) }
            if (map.isEmpty()) DEFAULT_CONFIG else map
        } catch (_: Exception) {
            DEFAULT_CONFIG
        }
    }
}
