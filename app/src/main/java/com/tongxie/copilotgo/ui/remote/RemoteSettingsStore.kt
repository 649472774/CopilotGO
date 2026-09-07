package com.tongxie.copilotgo.ui.remote

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import java.io.IOException

private val Context.remotePreferences by preferencesDataStore("remote_web_preferences")

internal interface RemoteSettings {
    val preferences: Flow<RemotePreferences>
    suspend fun update(value: RemotePreferences)
}

internal class RemoteSettingsStore(context: Context) : RemoteSettings {
    private val store = context.applicationContext.remotePreferences

    override val preferences = store.data.map { values ->
        val mode = values[NETWORK_MODE]
        val networkMode = when (mode) {
            null, RemoteNetworkMode.SYSTEM.name -> RemoteNetworkMode.SYSTEM
            RemoteNetworkMode.APP_PROXY.name -> RemoteNetworkMode.APP_PROXY
            else -> throw IOException("Unknown Remote network mode")
        }
        RemotePreferences(
            desktop = values[DESKTOP] ?: false,
            immersive = values[IMMERSIVE] ?: true,
            networkMode = networkMode
        )
    }

    override suspend fun update(value: RemotePreferences) {
        store.edit {
            it[DESKTOP] = value.desktop
            it[IMMERSIVE] = value.immersive
            it[NETWORK_MODE] = value.networkMode.name
        }
    }

    companion object {
        private val DESKTOP: Preferences.Key<Boolean> = booleanPreferencesKey("desktop")
        private val IMMERSIVE: Preferences.Key<Boolean> = booleanPreferencesKey("immersive")
        private val NETWORK_MODE: Preferences.Key<String> = stringPreferencesKey("network_mode")
    }
}
