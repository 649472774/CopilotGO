package com.tongxie.copilotgo.data.proxy

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.proxyDataStore by preferencesDataStore("proxy_prefs")

class ProxySettingsStore(private val context: Context) {

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_TYPE = stringPreferencesKey("type")
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_PORT = intPreferencesKey("port")
    }

    private fun androidx.datastore.preferences.core.Preferences.toProxyConfig() = ProxyConfig(
        enabled = this[KEY_ENABLED] ?: false,
        type = this[KEY_TYPE]?.let { runCatching { ProxyType.valueOf(it) }.getOrNull() }
            ?: ProxyType.HTTP,
        host = this[KEY_HOST] ?: "127.0.0.1",
        port = this[KEY_PORT] ?: 7890
    )

    private val _config: MutableStateFlow<ProxyConfig> = MutableStateFlow(
        runBlocking { context.proxyDataStore.data.first().toProxyConfig() }
    )

    val config: StateFlow<ProxyConfig> = _config

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        storeScope.launch {
            context.proxyDataStore.data
                .map { it.toProxyConfig() }
                .collect { _config.value = it }
        }
    }

    suspend fun update(c: ProxyConfig) {
        context.proxyDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = c.enabled
            prefs[KEY_TYPE] = c.type.name
            prefs[KEY_HOST] = c.host
            prefs[KEY_PORT] = c.port
        }
        _config.value = c
    }
}
