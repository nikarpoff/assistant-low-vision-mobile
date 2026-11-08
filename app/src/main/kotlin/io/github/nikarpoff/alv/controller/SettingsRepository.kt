package io.github.nikarpoff.alv.controller

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.nikarpoff.alv.model.Settings
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Settings of the user and the device in DataStore, the private storage of the app (MOB-9).
 *
 * A value never saved takes its default: the server address and the device token are set at build
 * time ([defaultServerUrl], [defaultDeviceToken]), the passive mode is off on first launch (MOB-14).
 * The device pseudonym `device_id` is a random UUID made on first launch (NFR-8).
 * It is also the storage of [PassiveModeSetting].
 */
class SettingsRepository(
    context: Context,
    private val defaultServerUrl: String,
    private val defaultDeviceToken: String,
) : PassiveModeSetting.Store {
    private val store = context.applicationContext.settingsStore

    /** Saved settings with the defaults for what was never saved. */
    suspend fun loadSettings(): Settings {
        val prefs = store.data.first()
        val deviceId = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { id ->
            store.edit { it[DEVICE_ID] = id }
        }
        return Settings(
            deviceId = deviceId,
            serverUrl = prefs[SERVER_URL] ?: defaultServerUrl,
            deviceToken = prefs[DEVICE_TOKEN] ?: defaultDeviceToken,
            passiveMode = prefs[PASSIVE_MODE] ?: false,
            participantLabel = prefs[LABEL] ?: "",
            showDebugPanel = prefs[DEBUG_PANEL] ?: false,
        )
    }

    /** Saves everything but `device_id`, which never changes. */
    suspend fun saveSettings(settings: Settings) {
        store.edit {
            it[SERVER_URL] = settings.serverUrl.trim()
            it[DEVICE_TOKEN] = settings.deviceToken.trim()
            it[PASSIVE_MODE] = settings.passiveMode
            it[LABEL] = settings.participantLabel.trim()
            it[DEBUG_PANEL] = settings.showDebugPanel
        }
    }

    override suspend fun load(): Boolean? = store.data.first()[PASSIVE_MODE]

    override suspend fun save(enabled: Boolean) {
        store.edit { it[PASSIVE_MODE] = enabled }
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val SERVER_URL = stringPreferencesKey("server_url")
        val DEVICE_TOKEN = stringPreferencesKey("device_token")
        val PASSIVE_MODE = booleanPreferencesKey("passive_mode")
        val LABEL = stringPreferencesKey("participant_label")
        val DEBUG_PANEL = booleanPreferencesKey("show_debug_panel")
    }
}
