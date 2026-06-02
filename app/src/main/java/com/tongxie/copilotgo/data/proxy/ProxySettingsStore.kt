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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.proxyDataStore by preferencesDataStore("proxy_prefs")

/**
 * 代理配置存储。
 *
 * v0.1.11 修复（Bug 5）：移除了构造里的 `runBlocking { dataStore.data.first() }`。
 * Application.onCreate() 在 Main 线程创建 AppContainer，再创建本类，runBlocking 会
 * 同步阻塞 Main 一直到 DataStore 完成首次冷启动加载（可达数百 ms，慢机上可能命中
 * 5s ANR 阈值）。现在 `_config` 用空 [ProxyConfig] 初始化，真实值由 init {} 异步 collect
 * 后端持续覆盖；[HttpClientProvider] 已 observe [config] flow，首次 emit 会自动重建客户端。
 */
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

    // 初始用默认值（关闭状态），DataStore 加载后由 init {} 的 collect 覆盖。
    // 默认 enabled=false → 在覆盖之前网络层走直连，行为合理。
    private val _config: MutableStateFlow<ProxyConfig> = MutableStateFlow(ProxyConfig())

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
