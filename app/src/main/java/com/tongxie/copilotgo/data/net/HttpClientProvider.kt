package com.tongxie.copilotgo.data.net

import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Authenticator as NetworkAuthenticator

interface HttpClientProvider {
    val client: OkHttpClient
    suspend fun awaitReady() = Unit

    fun clientFor(config: ProxyConfig): OkHttpClient =
        configureProxy(client.newBuilder(), config).build()
}

internal fun configureProxy(builder: OkHttpClient.Builder, config: ProxyConfig): OkHttpClient.Builder {
    require(!config.enabled || config.isValid()) { "代理地址或端口无效" }
    builder.proxyAuthenticator(Authenticator.NONE)
    if (!config.enabled) return builder.proxy(Proxy.NO_PROXY)
    val type = if (config.type == ProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS
    builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(config.host, config.port)))
    if (config.type == ProxyType.HTTP && config.requiresAuth) {
        builder.proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) null
            else response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(config.username, config.password))
                .build()
        }
    }
    return builder
}

class ProxyAwareHttpClientProvider(
    private val baseBuilder: () -> OkHttpClient.Builder,
    private val proxyConfigFlow: StateFlow<ProxyConfig>,
    scope: CoroutineScope,
    private val readiness: StateFlow<Boolean>? = null,
    private val configurationError: StateFlow<String?>? = null
) : HttpClientProvider {
    private val guard = Any()
    private var applied: ProxyConfig = proxyConfigFlow.value
    @Volatile
    override var client: OkHttpClient = buildClient(applied)
        private set

    init {
        scope.launch {
            proxyConfigFlow.collect { config -> apply(config) }
        }
    }

    override suspend fun awaitReady() {
        readiness?.first { it }
        configurationError?.value?.let { throw IOException(it) }
        withContext(Dispatchers.IO) { apply(proxyConfigFlow.value) }
    }

    override fun clientFor(config: ProxyConfig): OkHttpClient {
        if (config.enabled && config.type == ProxyType.SOCKS5 && config.requiresAuth && config != applied) {
            throw IllegalArgumentException("请先保存 SOCKS5 认证配置，再测试连接")
        }
        return configureProxy(baseBuilder(), config).build()
    }

    private fun apply(config: ProxyConfig) = synchronized(guard) {
        if (config == applied) return@synchronized
        val next = buildClient(config)
        val old = client
        client = next
        applied = config
        old.connectionPool.evictAll()
        // Do not shut down dispatchers: an in-flight call can still need them on retry.
    }

    private fun buildClient(config: ProxyConfig): OkHttpClient {
        configureSocksAuthentication(config)
        return configureProxy(baseBuilder(), config)
            .addInterceptor { chain ->
                if (readiness?.value == false || configurationError?.value != null) {
                    throw IOException("代理配置尚未就绪，请稍后重试")
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    private fun configureSocksAuthentication(config: ProxyConfig) = synchronized(authenticatorLock) {
        val needsSocks = config.enabled && config.type == ProxyType.SOCKS5 && config.requiresAuth
        installedConfig = config.takeIf { needsSocks }
        if (!needsSocks || installedAuthenticator != null) return@synchronized
        // Android exposes no getDefault(). Install once, then revoke by clearing the scoped config.
        val authenticator = object : NetworkAuthenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val selected = installedConfig ?: return null
                val hostMatches = requestingHost.equals(selected.host, ignoreCase = true) ||
                    requestingSite?.hostAddress == selected.host
                val socksRequest = requestingProtocol.equals("SOCKS5", ignoreCase = true)
                return if (hostMatches && requestingPort == selected.port && socksRequest) {
                    PasswordAuthentication(selected.username, selected.password.toCharArray())
                } else {
                    null
                }
            }
        }
        NetworkAuthenticator.setDefault(authenticator)
        installedAuthenticator = authenticator
    }

    companion object {
        private val authenticatorLock = Any()
        private var installedAuthenticator: NetworkAuthenticator? = null
        @Volatile
        private var installedConfig: ProxyConfig? = null
    }
}
