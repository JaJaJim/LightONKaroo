package io.github.JaJaJim.lightonkaroo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.JaJaJim.lightonkaroo.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "light_controller_settings")

class PreferencesRepository(private val context: Context) {

    companion object {
        private const val TAG = "PreferencesRepo"
        private val SETTINGS_KEY = stringPreferencesKey("settings")
        private val LAST_RUN_VERSION_CODE_KEY = intPreferencesKey("last_run_version_code")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val settingsFlow: Flow<LightControllerSettings> = context.dataStore.data.map { preferences ->
        getCurrentSettings(preferences)
    }

    suspend fun checkAndAutoCleanStaleVersionData() {
        try {
            val preferences = context.dataStore.data.first()
            val lastRunVersion = preferences[LAST_RUN_VERSION_CODE_KEY] ?: 0
            val currentVersion = BuildConfig.VERSION_CODE

            if (lastRunVersion < currentVersion) {
                Timber.i("$TAG: 🧹 App update / new install detected (lastVersion=$lastRunVersion, currentVersion=$currentVersion). Clearing stale version JSON data...")
                context.dataStore.edit { prefs ->
                    prefs.clear()
                    prefs[LAST_RUN_VERSION_CODE_KEY] = currentVersion
                }
                Timber.i("$TAG: 🧹 Stale version JSON data cleared successfully!")
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed version auto-cleanup check")
        }
    }

    suspend fun updateSettings(settings: LightControllerSettings) {
        context.dataStore.edit { preferences ->
            preferences[SETTINGS_KEY] = json.encodeToString(settings)
            preferences[LAST_RUN_VERSION_CODE_KEY] = BuildConfig.VERSION_CODE
        }
    }

    suspend fun resetToFactoryDefaults() {
        context.dataStore.edit { preferences ->
            preferences.clear()
            preferences[LAST_RUN_VERSION_CODE_KEY] = BuildConfig.VERSION_CODE
        }
    }

    private fun getCurrentSettings(preferences: Preferences): LightControllerSettings {
        val settingsJson = preferences[SETTINGS_KEY]
        return if (settingsJson != null) {
            try {
                json.decodeFromString<LightControllerSettings>(settingsJson)
            } catch (_: Exception) {
                LightControllerSettings()
            }
        } else {
            LightControllerSettings()
        }
    }
}
