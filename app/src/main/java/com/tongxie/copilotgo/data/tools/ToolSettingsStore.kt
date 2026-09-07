package com.tongxie.copilotgo.data.tools

import android.content.Context
import com.tongxie.copilotgo.data.storage.CredentialVault
import com.tongxie.copilotgo.data.storage.SecretVault
import com.tongxie.copilotgo.data.tools.net.ToolNetworkPolicy
import com.tongxie.copilotgo.data.tools.net.ToolNetworkException
import com.tongxie.copilotgo.data.tools.net.ToolUrlGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID

class ToolSettingsStore(
    private val vault: SecretVault,
    scope: CoroutineScope
) {
    constructor(context: Context, scope: CoroutineScope) : this(CredentialVault(context), scope)

    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }
    private val mutableState = MutableStateFlow(ToolSettingsState())
    val state = mutableState.asStateFlow()
    private val mutableRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions = mutableRevisions.asStateFlow()
    private var stored: StoredSettings? = null
    private var nextGeneration = 1L
    private var hasLoadedRecord = false

    init {
        scope.launch { reload() }
    }

    suspend fun awaitReady(): ToolSettingsSnapshot {
        val ready = state.first { !it.loading }
        ready.problem?.let { throw ToolException(it) }
        return ready.snapshot ?: toolFailure(ToolProblemCode.STORAGE, "工具设置尚未载入，请重试")
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableRevisions.value = emptyMap()
            mutableState.value = mutableState.value.copy(loading = true, problem = null)
            try {
                val record = vault.read(NAMESPACE)
                if (record == null && hasLoadedRecord) throw IOException("Previously loaded tool settings record is missing")
                if (record != null && record.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
                    throw IOException("Tool settings record is too large")
                }
                val loaded = record?.let { json.decodeFromString<StoredSettings>(it) }
                    ?: StoredSettings()
                validateStored(loaded)
                val current = if (record == null) loaded else reissueGenerations(loaded)
                persist(current)
                publish(current)
            } catch (e: CancellationException) {
                markStorageFailure()
                throw e
            } catch (_: IOException) {
                markStorageFailure()
            } catch (_: GeneralSecurityException) {
                markStorageFailure()
            } catch (_: IllegalArgumentException) {
                markStorageFailure()
            } catch (_: IllegalStateException) {
                markStorageFailure()
            }
        }
    }

    suspend fun updateWeb(
        draft: WebToolSettingsDraft,
        expectedRevision: Long,
        credential: CredentialUpdate = CredentialUpdate.Keep
    ): WebToolSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = requireStored()
            ensureRevision(current.web.config.revision, expectedRevision)
            val secret = updatedSecret(current.web.credential, credential)
            if (draft.provider == SearchProvider.EXA_API_KEY && secret == null) {
                toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "使用自有 Exa 密钥前，请先填写密钥")
            }
            val config = WebToolSettings(
                revision = current.nextRevision,
                searchEnabled = draft.searchEnabled,
                pageReaderEnabled = draft.pageReaderEnabled,
                provider = draft.provider,
                externalSharingConsent = draft.externalSharingConsent,
                credentialState = credentialState(secret)
            )
            val next = current.copy(
                nextRevision = increment(current.nextRevision),
                web = StoredWeb(config, secret)
            )
            commit(next, ToolSettingsLimits.WEB_CONFIGURATION_ID)
            config
        }
    }

    suspend fun saveServer(
        serverId: String?,
        expectedRevision: Long?,
        draft: McpServerDraft,
        credential: CredentialUpdate = CredentialUpdate.Keep
    ): McpServerSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = requireStored()
            val previous = if (serverId == null) {
                if (expectedRevision != null || current.servers.size >= ToolSettingsLimits.MAX_SERVERS) {
                    toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "MCP 服务器数量已达上限，或新增配置版本无效")
                }
                null
            } else {
                current.servers.firstOrNull { it.config.id == serverId }
                    ?: staleConfiguration()
            }
            if (previous != null) ensureRevision(previous.config.revision, expectedRevision)
            val endpoint = validateDraft(draft)
            val authHeader = normalizedAuthHeader(draft.authMode, draft.authHeaderName)
            val endpointChanged = previous != null && previous.config.endpoint != endpoint
            val credentialBindingChanged = endpointChanged || previous?.config?.let {
                it.authMode != draft.authMode || !it.authHeaderName.equals(authHeader, ignoreCase = true)
            } == true
            if (credentialBindingChanged && previous?.credential != null && credential == CredentialUpdate.Keep) {
                toolFailure(
                    ToolProblemCode.INVALID_CONFIGURATION,
                    "地址或认证方式已变更；请明确移除或重新填写密钥，不能沿用旧地址凭据"
                )
            }
            val secret = updatedSecret(previous?.credential, credential)
            if (draft.authMode == McpAuthMode.NONE && secret != null) {
                toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "关闭认证时请同时移除已保存的凭据")
            }
            if (draft.authMode != McpAuthMode.NONE && secret == null) {
                toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "所选认证方式需要凭据")
            }
            val id = serverId ?: UUID.randomUUID().toString().replace("-", "").take(16)
            val config = McpServerSettings(
                id = id,
                revision = current.nextRevision,
                label = draft.label.trim(),
                endpoint = endpoint,
                enabled = draft.enabled,
                networkTrust = draft.networkTrust,
                authMode = draft.authMode,
                authHeaderName = authHeader,
                credentialState = credentialState(secret),
                enabledTools = if (endpointChanged) emptySet() else draft.enabledTools.toSet(),
                transport = draft.transport
            )
            val next = current.copy(
                nextRevision = increment(current.nextRevision),
                servers = current.servers.filterNot { it.config.id == id } + StoredServer(config, secret)
            )
            commit(next, id)
            config
        }
    }

    suspend fun deleteServer(serverId: String, expectedRevision: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = requireStored()
            val previous = current.servers.firstOrNull { it.config.id == serverId }
                ?: staleConfiguration()
            ensureRevision(previous.config.revision, expectedRevision)
            commit(
                current.copy(
                    nextRevision = increment(current.nextRevision),
                    servers = current.servers.filterNot { it.config.id == serverId }
                ),
                serverId
            )
        }
    }

    fun isCurrent(configurationId: String, revision: Long): Boolean =
        revisions.value[configurationId] == revision

    internal suspend fun <T> withServer(
        serverId: String,
        expectedRevision: Long,
        requireEnabled: Boolean = true,
        block: suspend (ToolEndpointLease) -> T
    ): T {
        val lease = mutex.withLock {
            val server = requireStored().servers.firstOrNull { it.config.id == serverId }
                ?: staleConfiguration()
            ensureRevision(server.config.revision, expectedRevision)
            if (requireEnabled && !server.config.enabled) {
                toolFailure(ToolProblemCode.DISABLED, "此 MCP 服务器已停用")
            }
            ToolEndpointLease(
                server.config.id, server.config.revision, server.config.endpoint,
                server.config.networkTrust, server.config.authMode,
                server.config.authHeaderName, server.credential
            )
        }
        return withCurrent(lease.configurationId, lease.revision) { block(lease) }
    }

    internal suspend fun <T> withWeb(
        expectedRevision: Long,
        block: suspend (WebToolSettings, ToolEndpointLease) -> T
    ): T {
        val web = mutex.withLock {
            requireStored().web.also { ensureRevision(it.config.revision, expectedRevision) }
        }
        if (!web.config.externalSharingConsent) {
            toolFailure(ToolProblemCode.CONSENT_REQUIRED, "请先在联网工具设置中确认搜索词和网址的对外发送范围")
        }
        val usesKey = web.config.provider == SearchProvider.EXA_API_KEY
        val lease = ToolEndpointLease(
            ToolSettingsLimits.WEB_CONFIGURATION_ID, web.config.revision,
            WebProviderDisclosure.EXA_ENDPOINT, McpNetworkTrust.PUBLIC,
            if (usesKey) McpAuthMode.CUSTOM_HEADER else McpAuthMode.NONE,
            if (usesKey) "x-api-key" else "",
            web.credential.takeIf { usesKey }
        )
        return withCurrent(lease.configurationId, lease.revision) { block(web.config, lease) }
    }

    internal fun assertCurrent(lease: ToolEndpointLease) {
        if (!isCurrent(lease.configurationId, lease.revision)) staleConfiguration()
    }

    private suspend fun <T> withCurrent(
        configurationId: String,
        revision: Long,
        block: suspend () -> T
    ): T = coroutineScope {
        if (!isCurrent(configurationId, revision)) staleConfiguration()
        val operation = async(start = CoroutineStart.UNDISPATCHED) { block() }
        val invalidation = launch(start = CoroutineStart.UNDISPATCHED) {
            revisions.first { it[configurationId] != revision }
            operation.cancel(ConfigurationInvalidated())
        }
        try {
            operation.await().also {
                if (!isCurrent(configurationId, revision)) staleConfiguration()
            }
        } catch (_: ConfigurationInvalidated) {
            staleConfiguration()
        } finally {
            invalidation.cancel()
        }
    }

    private suspend fun commit(next: StoredSettings, invalidatedId: String) {
        val encoded = encode(next)
        nextGeneration = maxOf(nextGeneration, next.nextRevision)
        // Revoke before disk IO: a delete/edit must stop using an already-captured key immediately.
        mutableRevisions.value = mutableRevisions.value - invalidatedId
        try {
            vault.write(NAMESPACE, encoded)
            if (vault.read(NAMESPACE) != encoded) throw IOException("Tool settings verification failed")
            publish(next)
        } catch (e: CancellationException) {
            markStorageFailure()
            throw e
        } catch (_: IOException) {
            storageFailure()
        } catch (_: GeneralSecurityException) {
            storageFailure()
        } catch (_: IllegalArgumentException) {
            storageFailure()
        } catch (_: IllegalStateException) {
            storageFailure()
        }
    }

    private suspend fun persist(value: StoredSettings) {
        val payload = encode(value)
        vault.write(NAMESPACE, payload)
        if (vault.read(NAMESPACE) != payload) throw IOException("Tool settings verification failed")
    }

    private fun encode(value: StoredSettings): String {
        val payload = json.encodeToString(StoredSettings.serializer(), value)
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
            toolFailure(ToolProblemCode.TOO_LARGE, "工具配置超过安全存储大小限制，请减少服务器或密钥长度")
        }
        return payload
    }

    private fun publish(value: StoredSettings) {
        nextGeneration = maxOf(nextGeneration, value.nextRevision)
        hasLoadedRecord = true
        stored = value
        val snapshot = ToolSettingsSnapshot(value.web.config, value.servers.map { it.config })
        mutableState.value = ToolSettingsState(loading = false, snapshot = snapshot)
        mutableRevisions.value = buildMap {
            put(ToolSettingsLimits.WEB_CONFIGURATION_ID, value.web.config.revision)
            value.servers.forEach { put(it.config.id, it.config.revision) }
        }
    }

    private fun requireStored(): StoredSettings {
        state.value.problem?.let { throw ToolException(it) }
        return stored?.takeUnless { state.value.loading }
            ?: toolFailure(ToolProblemCode.STORAGE, "工具设置尚未载入，请稍后重试")
    }

    private fun reissueGenerations(value: StoredSettings): StoredSettings {
        var next = maxOf(value.nextRevision, nextGeneration)
        fun issue(): Long = next.also { next = increment(next) }
        val web = value.web.copy(config = value.web.config.copy(revision = issue()))
        val servers = value.servers.map { it.copy(config = it.config.copy(revision = issue())) }
        // A fast failed edit/reload must not make an old captured credential current again,
        // even if StateFlow observers conflate the intermediate revoked state.
        nextGeneration = next
        return value.copy(nextRevision = next, web = web, servers = servers)
    }

    private fun markStorageFailure() {
        stored = null
        mutableRevisions.value = emptyMap()
        mutableState.value = mutableState.value.copy(
            loading = false,
            problem = ToolProblem(ToolProblemCode.STORAGE, "无法读取或保存工具设置，已阻止工具联网；原记录未被删除，请重试")
        )
    }

    private fun storageFailure(): Nothing {
        markStorageFailure()
        throw ToolException(checkNotNull(state.value.problem))
    }

    private fun validateStored(value: StoredSettings) {
        require(value.formatVersion == 1 && value.nextRevision > value.web.config.revision)
        require(value.web.config.revision > 0 && value.servers.size <= ToolSettingsLimits.MAX_SERVERS)
        require(value.web.config.credentialState == credentialState(value.web.credential))
        value.web.credential?.let(::validateSecret)
        require(value.web.config.provider != SearchProvider.EXA_API_KEY || value.web.credential != null)
        require(value.servers.map { it.config.id }.distinct().size == value.servers.size)
        value.servers.forEach {
            require(it.config.id.matches(Regex("[a-f0-9]{16}")))
            require(it.config.revision > 0 && it.config.revision < value.nextRevision)
            require(validateDraft(McpServerDraft(it.config)) == it.config.endpoint)
            require(it.config.authHeaderName == normalizedAuthHeader(it.config.authMode, it.config.authHeaderName))
            require(it.config.credentialState == credentialState(it.credential))
            require((it.config.authMode == McpAuthMode.NONE) == (it.credential == null))
            it.credential?.let(::validateSecret)
        }
    }

    private fun validateDraft(draft: McpServerDraft): String {
        if (draft.transport != McpTransport.STREAMABLE_HTTP) {
            toolFailure(
                ToolProblemCode.UNSUPPORTED_TRANSPORT,
                "Android 仅支持远程 Streamable HTTP MCP；桌面 stdio 或旧 HTTP+SSE 请使用远程 HTTPS 桥接服务"
            )
        }
        if (draft.label.isBlank() || draft.label.length > ToolSettingsLimits.MAX_LABEL_CHARS ||
            draft.label.any { it.isISOControl() } ||
            draft.endpoint.length > ToolSettingsLimits.MAX_ENDPOINT_CHARS
        ) {
            toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "服务器名称或 HTTPS 地址无效、过长")
        }
        if (draft.enabledTools.size > ToolSettingsLimits.MAX_SELECTED_TOOLS ||
            draft.enabledTools.any {
                it.isBlank() || it.length > ToolSettingsLimits.MAX_TOOL_NAME_CHARS || it.any(Char::isISOControl)
            }
        ) {
            toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "选择的工具数量或工具名称无效")
        }
        val url = try {
            ToolUrlGuard.parse(
                draft.endpoint.trim(),
                if (draft.networkTrust == McpNetworkTrust.PUBLIC) {
                    ToolNetworkPolicy.PUBLIC_HTTPS
                } else {
                    ToolNetworkPolicy.TRUSTED_LAN_HTTPS
                }
            )
        } catch (e: ToolNetworkException) {
            throw ToolException(e.toToolProblem())
        }
        if (url.queryParameterNames.any { key ->
                key.lowercase().replace("-", "").replace("_", "") in SECRET_QUERY_NAMES
            }
        ) {
            toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "请将密钥保存在认证字段，不要放入服务器网址")
        }
        if (url.toString().length > ToolSettingsLimits.MAX_ENDPOINT_CHARS) {
            toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "编码后的服务器地址超过 2048 字符，请缩短地址")
        }
        normalizedAuthHeader(draft.authMode, draft.authHeaderName)
        return url.toString()
    }

    private fun normalizedAuthHeader(mode: McpAuthMode, name: String): String = when (mode) {
        McpAuthMode.NONE -> ""
        McpAuthMode.BEARER -> "Authorization"
        McpAuthMode.CUSTOM_HEADER -> {
            val normalized = name.trim()
            val lower = normalized.lowercase()
            if (normalized.length !in 1..64 || !normalized.matches(HEADER_NAME) ||
                lower in RESERVED_HEADERS || lower.startsWith("proxy-") ||
                lower.startsWith("mcp-") || lower.startsWith("sec-")
            ) {
                toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "认证头名称无效或与受保护的 HTTP/MCP 请求头冲突")
            }
            normalized
        }
    }

    private fun updatedSecret(previous: String?, update: CredentialUpdate): String? = when (update) {
        CredentialUpdate.Keep -> previous
        CredentialUpdate.Remove -> null
        is CredentialUpdate.Replace -> update.value.also(::validateSecret)
    }

    private fun validateSecret(value: String) {
        if (value.length !in 1..ToolSettingsLimits.MAX_CREDENTIAL_CHARS ||
            value.first().isWhitespace() || value.last().isWhitespace() ||
            value.any { it.code !in 0x20..0x7e }
        ) {
            toolFailure(ToolProblemCode.INVALID_CONFIGURATION, "密钥为空、过长或含不安全的请求头字符")
        }
    }

    private fun credentialState(value: String?): ToolCredentialState =
        if (value == null) ToolCredentialState.MISSING else ToolCredentialState.CONFIGURED

    private fun ensureRevision(actual: Long, expected: Long?) {
        if (actual != expected) staleConfiguration()
    }

    private fun increment(value: Long): Long {
        if (value == Long.MAX_VALUE) toolFailure(ToolProblemCode.STORAGE, "工具配置版本已达上限")
        return value + 1
    }

    private class ConfigurationInvalidated : CancellationException("Tool configuration changed")

    @Serializable
    private data class StoredSettings(
        val formatVersion: Int = 1,
        val nextRevision: Long = 2,
        val web: StoredWeb = StoredWeb(),
        val servers: List<StoredServer> = emptyList()
    ) {
        override fun toString(): String = "StoredToolSettings(<redacted>)"
    }

    @Serializable
    private data class StoredWeb(val config: WebToolSettings = WebToolSettings(), val credential: String? = null) {
        override fun toString(): String = "StoredWeb(<redacted>)"
    }

    @Serializable
    private data class StoredServer(val config: McpServerSettings, val credential: String? = null) {
        override fun toString(): String = "StoredServer(<redacted>)"
    }

    companion object {
        internal const val NAMESPACE = "tools-settings-v1"
        private const val MAX_RECORD_BYTES = 64 * 1024
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
    }
}

/** A scoped credential snapshot; all uses are guarded by the store's revision. */
internal class ToolEndpointLease(
    val configurationId: String,
    val revision: Long,
    val endpoint: String,
    val networkTrust: McpNetworkTrust,
    private val authMode: McpAuthMode,
    private val authHeaderName: String,
    private val credential: String?
) {
    val hasCredential: Boolean get() = credential != null
    val networkPolicy: ToolNetworkPolicy get() =
        if (networkTrust == McpNetworkTrust.PUBLIC) ToolNetworkPolicy.PUBLIC_HTTPS else ToolNetworkPolicy.TRUSTED_LAN_HTTPS

    fun authorize(builder: okhttp3.Request.Builder) {
        credential?.let {
            if (builder.build().url.toString() != endpoint) {
                toolFailure(ToolProblemCode.UNSAFE_DESTINATION, "认证信息只能发送到已配置的确切 MCP 地址")
            }
            builder.header(authHeaderName, if (authMode == McpAuthMode.BEARER) "Bearer $it" else it)
        }
    }

    fun redact(value: String): String =
        if (credential == null) value else value.replace(credential, "[redacted]")

    override fun toString(): String = "ToolEndpointLease($configurationId, revision=$revision, <redacted>)"
}

internal fun staleConfiguration(): Nothing =
    toolFailure(ToolProblemCode.CONFIGURATION_CHANGED, "工具配置已变更或删除，请重新发现工具并确认本次操作")
