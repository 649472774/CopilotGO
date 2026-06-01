package com.tongxie.copilotgo.data.net

import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
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
        }
        return builder.build()
    }
}
