package com.tongxie.copilotgo.data.net

import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Authenticator as OkHttpAuthenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.Authenticator as JavaNetAuthenticator
import java.net.Authenticator.RequestorType
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy

interface HttpClientProvider {
    val client: OkHttpClient
}

class ProxyAwareHttpClientProvider(
    private val baseBuilder: () -> OkHttpClient.Builder,
    proxyConfigFlow: StateFlow<ProxyConfig>,
    scope: CoroutineScope
) : HttpClientProvider {

    @Volatile
    override var client: OkHttpClient = buildClient(proxyConfigFlow.value)
        private set

    init {
        scope.launch {
            proxyConfigFlow.collect { config ->
                val old = client
                client = buildClient(config)
                runCatching { old.connectionPool.evictAll() }
                runCatching { old.dispatcher.executorService.shutdown() }
            }
        }
    }

    private fun buildClient(config: ProxyConfig): OkHttpClient {
        val builder = baseBuilder()
        if (config.enabled && config.isValid()) {
            val proxyType = when (config.type) {
                ProxyType.HTTP -> Proxy.Type.HTTP
                ProxyType.SOCKS5 -> Proxy.Type.SOCKS
            }
            builder.proxy(Proxy(proxyType, InetSocketAddress(config.host, config.port)))

            when (config.type) {
                ProxyType.HTTP -> {
                    clearSocksAuthenticator()
                    if (config.requiresAuth) {
                        builder.proxyAuthenticator(OkHttpAuthenticator { _, response ->
                            if (response.request.header("Proxy-Authorization") != null) {
                                null
                            } else {
                                response.request.newBuilder()
                                    .header(
                                        "Proxy-Authorization",
                                        Credentials.basic(config.username, config.password)
                                    )
                                    .build()
                            }
                        })
                    }
                }
                ProxyType.SOCKS5 -> {
                    if (config.requiresAuth) {
                        installSocksAuthenticator(config)
                    } else {
                        clearSocksAuthenticator()
                    }
                }
            }
        } else {
            builder.proxy(Proxy.NO_PROXY)
            clearSocksAuthenticator()
        }
        return builder.build()
    }

    private fun installSocksAuthenticator(config: ProxyConfig) {
        val authKey = SocksAuthKey(config.username, config.password)
        synchronized(authenticatorLock) {
            if (installedSocksAuthKey == authKey) return
            // SOCKS credentials are process-global in Java; OkHttp proxyAuthenticator is HTTP-only.
            // Gate on RequestorType.PROXY so these creds are never handed to non-proxy requestors.
            JavaNetAuthenticator.setDefault(object : JavaNetAuthenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? =
                    if (requestorType == RequestorType.PROXY) {
                        PasswordAuthentication(config.username, config.password.toCharArray())
                    } else {
                        null
                    }
            })
            installedSocksAuthKey = authKey
            authenticatorCleared = false
        }
    }

    private fun clearSocksAuthenticator() {
        synchronized(authenticatorLock) {
            if (installedSocksAuthKey == null && authenticatorCleared) return
            JavaNetAuthenticator.setDefault(null)
            installedSocksAuthKey = null
            authenticatorCleared = true
        }
    }

    private data class SocksAuthKey(val username: String, val password: String)

    companion object {
        private val authenticatorLock = Any()
        private var installedSocksAuthKey: SocksAuthKey? = null
        private var authenticatorCleared = false
    }
}
