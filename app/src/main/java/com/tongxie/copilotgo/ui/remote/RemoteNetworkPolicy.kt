package com.tongxie.copilotgo.ui.remote

import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyType
import okhttp3.HttpUrl

internal sealed interface RemoteProxyRoute {
    data object System : RemoteProxyRoute
    data class Override(val rule: String) : RemoteProxyRoute
}

internal sealed interface RemoteNetworkDecision {
    data class Ready(val route: RemoteProxyRoute, val label: String) : RemoteNetworkDecision
    data class Waiting(val label: String) : RemoteNetworkDecision
    data class Blocked(val problem: RemoteProblem) : RemoteNetworkDecision
}

internal object RemoteNetworkPolicy {
    fun decide(
        mode: RemoteNetworkMode,
        config: ProxyConfig?,
        initialized: Boolean,
        loadError: String?,
        overrideSupported: Boolean
    ): RemoteNetworkDecision {
        if (mode == RemoteNetworkMode.SYSTEM) {
            return RemoteNetworkDecision.Ready(RemoteProxyRoute.System, "系统网络（独立于应用代理）")
        }
        if (loadError != null) {
            return blocked("无法读取应用代理", "请先在应用的代理设置中修复错误。Remote 不会改为直连。")
        }
        if (config == null) {
            return blocked("应用代理未接入", "当前页面缺少应用代理配置，请返回后重试或明确选择系统网络。")
        }
        if (!initialized) return RemoteNetworkDecision.Waiting("正在等待应用代理初始化")
        if (!config.enabled) {
            return RemoteNetworkDecision.Ready(RemoteProxyRoute.System, "跟随应用：代理已关闭，使用系统网络")
        }
        if (!overrideSupported) {
            return blocked("当前 WebView 不支持代理", "请更新 Android System WebView。Remote 已暂停联网，不会自动直连。")
        }
        if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
            return blocked(
                "Remote 不支持带认证的代理",
                "WebView 不能安全复用应用的代理凭据。请配置无认证的本机代理，或明确选择系统网络。"
            )
        }
        val host = config.host
        if (host.isBlank() || host != host.trim() || host.any { it.isWhitespace() || it in "/\\?#@" }) {
            return blocked("代理地址无效", "请在应用代理设置中填写有效主机与端口。Remote 已暂停联网。")
        }
        if (config.port !in 1..65535) {
            return blocked("代理端口无效", "代理端口必须在 1–65535 之间。Remote 已暂停联网。")
        }
        val endpoint = try {
            HttpUrl.Builder().scheme("http").host(host).port(config.port).build()
        } catch (_: IllegalArgumentException) {
            return blocked("代理地址无效", "请在应用代理设置中填写有效主机与端口。Remote 已暂停联网。")
        }
        val normalizedHost = if (':' in endpoint.host) "[${endpoint.host}]" else endpoint.host
        if (endpoint.host.split('.').any { it.isEmpty() }) {
            return blocked("代理地址无效", "请检查主机名称。Remote 已暂停联网。")
        }
        val scheme = when (config.type) {
            ProxyType.HTTP -> "http"
            ProxyType.SOCKS5 -> "socks"
        }
        val rule = "$scheme://$normalizedHost:${config.port}"
        return RemoteNetworkDecision.Ready(
            RemoteProxyRoute.Override(rule),
            "应用代理 · ${config.type.name} · $normalizedHost:${config.port}"
        )
    }

    private fun blocked(title: String, detail: String) =
        RemoteNetworkDecision.Blocked(RemoteProblem(title, detail))
}
