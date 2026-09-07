package com.tongxie.copilotgo.data.tools.net

import com.tongxie.copilotgo.data.net.HttpClientProvider
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory

internal class ToolHttpsFixture : Closeable {
    val server = MockWebServer()
    val connects = CopyOnWriteArrayList<InetSocketAddress>()
    val publicAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
    val loopback: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    private val clients = mutableListOf<OkHttpClient>()
    private val certificate = HeldCertificate.Builder()
        .commonName("Controlled tool fixture")
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .addSubjectAlternativeName("tools.example.com")
        .addSubjectAlternativeName("other.example.com")
        .addSubjectAlternativeName("8.8.8.8")
        .build()
    private val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate).build()

    init {
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start(loopback, 0)
    }

    fun url(path: String = "/", host: String? = null): HttpUrl =
        server.url(path).newBuilder().host(host ?: "localhost").build()

    fun request(path: String = "/", host: String? = null): Request =
        Request.Builder().url(url(path, host)).build()

    fun response(body: String = "fixture"): MockResponse =
        MockResponse().setHeader("Content-Type", "text/plain").setBody(body)

    fun provider(configure: OkHttpClient.Builder.() -> Unit = {}): HttpClientProvider {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(fixtureDns { hostname ->
                check(hostname == "localhost")
                listOf(loopback)
            })
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .apply(configure)
            .build()
        clients += client
        return object : HttpClientProvider { override val client = client }
    }

    fun publicProvider(
        resolver: Dns = fixtureDns { listOf(publicAddress) },
        reportLogicalAddress: Boolean = true,
        configure: OkHttpClient.Builder.() -> Unit = {}
    ): HttpClientProvider = provider {
        dns(resolver)
        socketFactory(object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
                private var target: InetSocketAddress? = null

                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    val selected = endpoint as InetSocketAddress
                    connects += selected
                    // Test-only address mapping: no socket ever contacts the synthetic public IP.
                    // TLS still verifies the real hostname against the fixture certificate's SANs.
                    super.connect(InetSocketAddress(loopback, server.port), timeout)
                    target = selected
                }

                override fun getRemoteSocketAddress(): SocketAddress? =
                    if (reportLogicalAddress) target ?: super.getRemoteSocketAddress() else super.getRemoteSocketAddress()

                override fun getInetAddress(): InetAddress? =
                    if (reportLogicalAddress) target?.address ?: super.getInetAddress() else super.getInetAddress()

                override fun getPort(): Int =
                    if (reportLogicalAddress) target?.port ?: super.getPort() else super.getPort()
            }

            override fun createSocket(host: String, port: Int): Socket = unsupported()
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
            override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
            private fun unsupported(): Nothing = throw AssertionError("Fixture requires connect-time address selection")
        })
        configure()
    }

    override fun close() {
        clients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        server.close()
    }
}

internal fun fixtureDns(resolve: (String) -> List<InetAddress>): Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
}
