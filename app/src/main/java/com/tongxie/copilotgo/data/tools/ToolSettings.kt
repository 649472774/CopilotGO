package com.tongxie.copilotgo.data.tools

import kotlinx.serialization.Serializable

object ToolSettingsLimits {
    const val MAX_SERVERS = 16
    const val MAX_LABEL_CHARS = 80
    const val MAX_ENDPOINT_CHARS = 2048
    const val MAX_CREDENTIAL_CHARS = 4096
    const val MAX_SELECTED_TOOLS = 64
    const val MAX_TOOL_NAME_CHARS = 128
    const val WEB_CONFIGURATION_ID = "builtin-web"
}

@Serializable
enum class SearchProvider { EXA_KEYLESS, EXA_API_KEY }

@Serializable
enum class ToolCredentialState { MISSING, CONFIGURED }

@Serializable
enum class McpNetworkTrust { PUBLIC, LOCAL_NETWORK }

@Serializable
enum class McpAuthMode { NONE, BEARER, CUSTOM_HEADER }

@Serializable
enum class McpTransport { STREAMABLE_HTTP, HTTP_SSE, STDIO }

@Serializable
data class WebToolSettings(
    val revision: Long = 1,
    val searchEnabled: Boolean = true,
    val pageReaderEnabled: Boolean = true,
    val provider: SearchProvider = SearchProvider.EXA_KEYLESS,
    val externalSharingConsent: Boolean = false,
    val credentialState: ToolCredentialState = ToolCredentialState.MISSING
)

@Serializable
data class McpServerSettings(
    val id: String,
    val revision: Long,
    val label: String,
    val endpoint: String,
    val enabled: Boolean = false,
    val networkTrust: McpNetworkTrust = McpNetworkTrust.PUBLIC,
    val authMode: McpAuthMode = McpAuthMode.NONE,
    val authHeaderName: String = "",
    val credentialState: ToolCredentialState = ToolCredentialState.MISSING,
    val enabledTools: Set<String> = emptySet(),
    val transport: McpTransport = McpTransport.STREAMABLE_HTTP
)

@Serializable
data class ToolSettingsSnapshot(
    val web: WebToolSettings = WebToolSettings(),
    val servers: List<McpServerSettings> = emptyList()
)

data class ToolSettingsState(
    val loading: Boolean = true,
    val snapshot: ToolSettingsSnapshot? = null,
    val problem: ToolProblem? = null
)

data class WebToolSettingsDraft(
    val searchEnabled: Boolean,
    val pageReaderEnabled: Boolean,
    val provider: SearchProvider,
    val externalSharingConsent: Boolean
) {
    constructor(settings: WebToolSettings) : this(
        settings.searchEnabled, settings.pageReaderEnabled,
        settings.provider, settings.externalSharingConsent
    )
}

data class McpServerDraft(
    val label: String,
    val endpoint: String,
    val enabled: Boolean = false,
    val networkTrust: McpNetworkTrust = McpNetworkTrust.PUBLIC,
    val authMode: McpAuthMode = McpAuthMode.NONE,
    val authHeaderName: String = "",
    val enabledTools: Set<String> = emptySet(),
    val transport: McpTransport = McpTransport.STREAMABLE_HTTP
) {
    constructor(settings: McpServerSettings) : this(
        settings.label, settings.endpoint, settings.enabled, settings.networkTrust,
        settings.authMode, settings.authHeaderName, settings.enabledTools, settings.transport
    )
}

/** Not serializable and deliberately not a data class: forms must never save a key. */
sealed class CredentialUpdate {
    data object Keep : CredentialUpdate()
    data object Remove : CredentialUpdate()
    class Replace(internal val value: String) : CredentialUpdate() {
        override fun toString(): String = "CredentialUpdate.Replace(<redacted>)"
    }
}

object WebProviderDisclosure {
    const val EXA_ENDPOINT = "https://mcp.exa.ai/mcp?tools=web_search_exa"
    const val PROVIDER_NAME = "Exa"
    const val SHARING_NOTICE =
        "联网搜索会把本次搜索词发送给 Exa；网页读取会把所选网址发送给该网站。" +
            "不会自动发送完整对话、附件或 GitHub/Copilot 凭据。网页和工具返回内容均不可信。"
    const val FREE_TIER_NOTICE =
        "Exa 免密搜索受免费额度和速率限制，可能暂时不可用；不会自动购买服务或切换收费方案。"
}
