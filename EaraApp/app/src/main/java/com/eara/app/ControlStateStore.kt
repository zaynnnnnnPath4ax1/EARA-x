package com.eara.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.controlDataStore by preferencesDataStore(name = "eara_control_prefs")

/** Persists whether the user has CONTROL ON or OFF (mirrors the existing UI toggle). */
class ControlStateStore(private val context: Context) {

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("control_enabled")
        const val DEFAULT_ENABLED = false // first-launch default OFF; saved state (if any) always wins
    }

    val enabledFlow: Flow<Boolean> =
        context.controlDataStore.data.map { it[KEY_ENABLED] ?: DEFAULT_ENABLED }

    suspend fun isEnabled(): Boolean = enabledFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.controlDataStore.edit { it[KEY_ENABLED] = enabled }
    }
}
