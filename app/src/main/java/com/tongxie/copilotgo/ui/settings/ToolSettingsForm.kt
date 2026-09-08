package com.tongxie.copilotgo.ui.settings

import com.tongxie.copilotgo.data.tools.McpAuthMode
import com.tongxie.copilotgo.data.tools.McpServerDraft
import com.tongxie.copilotgo.data.tools.McpServerSettings
import com.tongxie.copilotgo.data.tools.McpTransport
import com.tongxie.copilotgo.data.tools.ToolCredentialState
import com.tongxie.copilotgo.data.tools.ToolSettingsLimits
import com.tongxie.copilotgo.data.tools.WebToolSettings
import com.tongxie.copilotgo.data.tools.WebToolSettingsDraft
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveredTool
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveryReport
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveryState
import java.net.URI
import java.net.URISyntaxException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A choice, not a credential. Secret text lives only in ToolSecretInputState. */
enum class ToolCredentialAction { KEEP, REPLACE, REMOVE }

enum class ToolSettingsInputIssue {
    LABEL_REQUIRED,
    LABEL_TOO_LONG,
    LABEL_CONTROL_CHARACTERS,
    ENDPOINT_REQUIRED,
    ENDPOINT_TOO_LONG,
    ENDPOINT_HTTPS_REQUIRED,
    ENDPOINT_INVALID,
    ENDPOINT_USER_INFO,
    ENDPOINT_SECRET_QUERY,
    HEADER_REQUIRED,
    HEADER_INVALID,
    TOO_MANY_TOOLS,
    UNSUPPORTED_TRANSPORT
}

data class ToolMcpValidation(
    val label: ToolSettingsInputIssue? = null,
    val endpoint: ToolSettingsInputIssue? = null,
    val header: ToolSettingsInputIssue? = null,
    val tools: ToolSettingsInputIssue? = null,
    val transport: ToolSettingsInputIssue? = null
) {
    val valid: Boolean get() = listOf(label, endpoint, header, tools, transport).all { it == null }
}

data class ToolSearchForm(
    val original: WebToolSettings,
    val draft: WebToolSettingsDraft = WebToolSettingsDraft(original),
    val credentialAction: ToolCredentialAction = ToolCredentialAction.KEEP
) {
    val dirty: Boolean
        get() = draft != WebToolSettingsDraft(original) || credentialAction != ToolCredentialAction.KEEP

    fun isStale(current: WebToolSettings?): Boolean = current?.revision != original.revision
}

data class ToolMcpForm(
    val serverId: String?,
    val original: McpServerSettings?,
    val draft: McpServerDraft = original?.let(::McpServerDraft) ?: McpServerDraft(label = "", endpoint = ""),
    val credentialAction: ToolCredentialAction = ToolCredentialAction.KEEP
) {
    val expectedRevision: Long? get() = original?.revision
    val dirty: Boolean
        get() = draft != (original?.let(::McpServerDraft) ?: McpServerDraft(label = "", endpoint = "")) ||
            credentialAction != ToolCredentialAction.KEEP
    val validation: ToolMcpValidation get() = validateToolMcpDraft(draft)
    val namespaceChanged: Boolean
        get() = original != null && (
            draft.endpoint != original.endpoint || draft.authMode != original.authMode ||
                !effectiveHeader(draft.authMode, draft.authHeaderName).equals(
                    effectiveHeader(original.authMode, original.authHeaderName), ignoreCase = true
                )
            )
    val needsCredentialDecision: Boolean
        get() = original?.credentialState == ToolCredentialState.CONFIGURED && namespaceChanged &&
            credentialAction == ToolCredentialAction.KEEP

    fun isStale(current: McpServerSettings?): Boolean =
        serverId != null && (current == null || current.revision != expectedRevision)

    fun edit(next: McpServerDraft): ToolMcpForm {
        val connectionChanged = next.endpoint != draft.endpoint ||
            next.authMode != draft.authMode || next.authHeaderName != draft.authHeaderName ||
            next.networkTrust != draft.networkTrust || next.transport != draft.transport
        return copy(draft = if (connectionChanged) next.copy(enabledTools = emptySet()) else next)
    }

    fun canSelectDiscoveredTools(discoveryRevision: Long, currentRevision: Long?): Boolean {
        val saved = original ?: return false
        return currentRevision == saved.revision && discoveryRevision == saved.revision &&
            credentialAction == ToolCredentialAction.KEEP &&
            draft.copy(enabledTools = saved.enabledTools) == McpServerDraft(saved)
    }
}

fun validateToolMcpDraft(draft: McpServerDraft): ToolMcpValidation = ToolMcpValidation(
    label = when {
        draft.label.isBlank() -> ToolSettingsInputIssue.LABEL_REQUIRED
        draft.label.length > ToolSettingsLimits.MAX_LABEL_CHARS -> ToolSettingsInputIssue.LABEL_TOO_LONG
        draft.label.any(Char::isISOControl) -> ToolSettingsInputIssue.LABEL_CONTROL_CHARACTERS
        else -> null
    },
    endpoint = validateToolEndpoint(draft.endpoint),
    header = when {
        draft.authMode != McpAuthMode.CUSTOM_HEADER -> null
        draft.authHeaderName.isBlank() -> ToolSettingsInputIssue.HEADER_REQUIRED
        draft.authHeaderName.length > 64 || !HEADER_NAME.matches(draft.authHeaderName) ||
            draft.authHeaderName.lowercase() in RESERVED_HEADERS ||
            listOf("proxy-", "mcp-", "sec-").any { draft.authHeaderName.startsWith(it, ignoreCase = true) } ->
            ToolSettingsInputIssue.HEADER_INVALID
        else -> null
    },
    tools = if (draft.enabledTools.size > ToolSettingsLimits.MAX_SELECTED_TOOLS) {
        ToolSettingsInputIssue.TOO_MANY_TOOLS
    } else null,
    transport = if (draft.transport != McpTransport.STREAMABLE_HTTP) {
        ToolSettingsInputIssue.UNSUPPORTED_TRANSPORT
    } else null
)

private fun validateToolEndpoint(value: String): ToolSettingsInputIssue? {
    if (value.isBlank()) return ToolSettingsInputIssue.ENDPOINT_REQUIRED
    if (value.length > ToolSettingsLimits.MAX_ENDPOINT_CHARS) return ToolSettingsInputIssue.ENDPOINT_TOO_LONG
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return ToolSettingsInputIssue.ENDPOINT_INVALID
    }
    if (!uri.scheme.equals("https", ignoreCase = true)) return ToolSettingsInputIssue.ENDPOINT_HTTPS_REQUIRED
    if (uri.rawUserInfo != null) return ToolSettingsInputIssue.ENDPOINT_USER_INFO
    val url = value.toHttpUrlOrNull()
    if (
        uri.rawFragment != null || uri.rawAuthority.isNullOrBlank() || value.any(Char::isISOControl) ||
        value != value.trim() || url == null || url.host.isBlank() || url.username.isNotEmpty() ||
        url.password.isNotEmpty()
    ) return ToolSettingsInputIssue.ENDPOINT_INVALID
    if (url.queryParameterNames.any {
            it.lowercase().replace("-", "").replace("_", "") in SECRET_QUERY_NAMES
        }
    ) return ToolSettingsInputIssue.ENDPOINT_SECRET_QUERY
    return null
}

private fun effectiveHeader(mode: McpAuthMode, header: String): String =
    if (mode == McpAuthMode.CUSTOM_HEADER) header else ""

fun currentToolDiscovery(server: McpServerSettings?, discovery: McpDiscoveryState?): McpDiscoveryReport? {
    if (server == null || discovery == null || discovery.loading || discovery.problem != null) return null
    if (discovery.configRevision != server.revision) return null
    return discovery.report?.takeIf { it.serverId == server.id && it.configRevision == server.revision }
}

fun McpDiscoveredTool.canBeSelected(): Boolean = supported && inputSchema != null && problem == null

fun isToolCredentialInputValid(value: String): Boolean =
    value.isNotEmpty() && value.length <= ToolSettingsLimits.MAX_CREDENTIAL_CHARS &&
        !value.first().isWhitespace() && !value.last().isWhitespace() &&
        value.all { it.code in 0x20..0x7e }

fun toolCredentialWillExist(action: ToolCredentialAction, saved: ToolCredentialState): Boolean = when (action) {
    ToolCredentialAction.KEEP -> saved == ToolCredentialState.CONFIGURED
    ToolCredentialAction.REPLACE -> true
    ToolCredentialAction.REMOVE -> false
}

private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private val RESERVED_HEADERS = setOf(
    "authorization", "host", "cookie", "cookie2", "connection", "content-type",
    "content-length", "transfer-encoding", "accept", "accept-encoding", "user-agent",
    "origin", "referer", "te", "trailer", "upgrade", "expect", "range"
)
private val SECRET_QUERY_NAMES = setOf(
    "key", "apikey", "exaapikey", "token", "accesstoken", "authorization", "password",
    "secret", "clientsecret", "signature", "credential"
)
