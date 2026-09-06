package com.tongxie.copilotgo.data.proxy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tongxie.copilotgo.data.storage.CredentialVault
import com.tongxie.copilotgo.data.storage.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val Context.proxyDataStore by preferencesDataStore("proxy_prefs")

class ProxySettingsStore(
    private val legacy: DataStore<Preferences>,
    private val vault: SecretVault,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    constructor(context: Context) : this(
        context.applicationContext.proxyDataStore, CredentialVault(context)
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _config = MutableStateFlow(ProxyConfig())
    val config = _config.asStateFlow()
    private val _initialized = MutableStateFlow(false)
    val initialized = _initialized.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError = _loadError.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        scope.launch {
            mutex.withLock {
                _initialized.value = false
                try {
                    val encrypted = vault.read(NAMESPACE)
                    val saved = if (encrypted != null) {
                        json.decodeFromString(ProxyConfig.serializer(), encrypted)
                    } else {
                        val prefs = legacy.data.first()
                        val type = prefs[KEY_TYPE]?.let { name ->
                            ProxyType.entries.firstOrNull { it.name == name }
                                ?: throw IllegalArgumentException("代理类型无效")
                        } ?: ProxyType.HTTP
                        ProxyConfig(
                            prefs[KEY_ENABLED] ?: false, type, prefs[KEY_HOST] ?: "127.0.0.1",
                            prefs[KEY_PORT] ?: 7890, prefs[KEY_USERNAME] ?: "", prefs[KEY_PASSWORD] ?: ""
                        ).also { persist(it) }
                    }
                    require(!saved.enabled || saved.isValid()) { "已保存的代理配置无效" }
                    clearLegacy()
                    _config.value = saved
                    _loadError.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _loadError.value = "无法读取代理配置，已阻止联网；请重试，原配置未被删除"
                } finally {
                    _initialized.value = true
                }
            }
        }
    }

    suspend fun update(c: ProxyConfig) = withContext(Dispatchers.IO) {
        require(!c.enabled || c.isValid()) { "代理地址或端口无效" }
        mutex.withLock {
            persist(c)
            clearLegacy()
            _config.value = c
            _loadError.value = null
            _initialized.value = true
        }
    }

    private suspend fun persist(config: ProxyConfig) {
        val payload = json.encodeToString(ProxyConfig.serializer(), config)
        vault.write(NAMESPACE, payload)
        check(vault.read(NAMESPACE) == payload) { "代理配置写入校验失败" }
    }

    private suspend fun clearLegacy() {
        if (legacy.data.first().asMap().isNotEmpty()) legacy.edit { it.clear() }
    }

    private companion object {
        const val NAMESPACE = "proxy"
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_TYPE = stringPreferencesKey("type")
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_PORT = intPreferencesKey("port")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_PASSWORD = stringPreferencesKey("password")
    }
}
