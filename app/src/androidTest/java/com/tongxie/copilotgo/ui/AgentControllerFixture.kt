package com.tongxie.copilotgo.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import com.tongxie.copilotgo.data.agent.AgentEngine
import com.tongxie.copilotgo.data.agent.AgentFunctionCall
import com.tongxie.copilotgo.data.agent.AgentLimits
import com.tongxie.copilotgo.data.agent.AgentModelTransport
import com.tongxie.copilotgo.data.agent.AgentPromptBuilder
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.agent.AgentToolCall
import com.tongxie.copilotgo.data.agent.AgentToolConfigurationChangedException
import com.tongxie.copilotgo.data.agent.AgentToolDescriptor
import com.tongxie.copilotgo.data.agent.AgentToolException
import com.tongxie.copilotgo.data.agent.AgentToolExecutor
import com.tongxie.copilotgo.data.agent.AgentToolIdentity
import com.tongxie.copilotgo.data.agent.AgentToolInvocation
import com.tongxie.copilotgo.data.agent.AgentToolKind
import com.tongxie.copilotgo.data.agent.AgentToolResult
import com.tongxie.copilotgo.data.agent.AgentToolSnapshot
import com.tongxie.copilotgo.data.agent.AgentToolValidation
import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.agent.SourceReference
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.CopilotTokenClient
import com.tongxie.copilotgo.data.auth.CredentialStore
import com.tongxie.copilotgo.data.auth.DeviceFlowClient
import com.tongxie.copilotgo.data.auth.StoredCredentials
import com.tongxie.copilotgo.data.auth.TokenStore
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.CopilotChatClient
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.SessionStore
import com.tongxie.copilotgo.data.tools.McpServerSettings
import com.tongxie.copilotgo.data.tools.ToolSettingsSnapshot
import com.tongxie.copilotgo.data.tools.ToolSettingsState
import com.tongxie.copilotgo.data.tools.WebToolSettings
import com.tongxie.copilotgo.ui.draft.ChatDraftStore
import com.tongxie.copilotgo.ui.viewmodel.ChatDraftsViewModel
import com.tongxie.copilotgo.ui.viewmodel.ChatViewModel
import com.tongxie.copilotgo.ui.viewmodel.LibraryFilesViewModel
import com.tongxie.copilotgo.ui.viewmodel.SessionListViewModel
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Real controllers, engine and stores; only the model, credentials and tool boundary are synthetic. */
internal class AgentControllerFixture(context: Context, val root: File) {
    val id = "controller-fixture"
    val mainJob = SupervisorJob()
    val ioJob = SupervisorJob()
    private val mainScope = CoroutineScope(mainJob + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(ioJob + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val paths = AppPaths(File(root, "sessions-root"))
    val store = SessionStore(paths, json, ioScope)
    val unexpectedRequests = AtomicInteger()
    private val provider = object : HttpClientProvider {
        override val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.host != "controller-fixture.invalid" || chain.request().url.encodedPath != "/models") {
                unexpectedRequests.incrementAndGet()
                throw IOException("Unexpected request in a controlled UI fixture")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(
                    """{"data":[{"id":"fixture-model","is_chat_default":true,"capabilities":{"type":"chat","supports":{"tool_calls":true}}}]}"""
                        .toResponseBody("application/json".toMediaType())
                ).build()
        }.build()
    }
    private val credentials = object : CredentialStore {
        private var value = StoredCredentials(
            "synthetic-controller-github",
            TokenStore.CachedCopilot(
                "synthetic-controller-copilot", System.currentTimeMillis() / 1_000 + 3_600,
                "fixture", "https://controller-fixture.invalid"
            )
        )
        override suspend fun readCredentials() = value
        override suspend fun writeCredentials(credentials: StoredCredentials) { value = credentials }
    }
    val auth = AuthRepository(credentials, DeviceFlowClient(provider, json), CopilotTokenClient(provider, json))
    val client = CopilotChatClient(provider, json, auth)
    val tools = ControllerTool()
    val modelRequests = CopyOnWriteArrayList<AgentChatRequest>()
    private val model = AgentModelTransport { request ->
        modelRequests += request
        if (request.messages.any { it.role == "tool" }) {
            flowOf(AgentStreamEvent.TextDelta("已读取受控工具资料 [S1]"), AgentStreamEvent.Completed("stop"))
        } else {
            flowOf(AgentStreamEvent.Completed(
                "tool_calls",
                listOf(AgentToolCall("fixture-call", AgentFunctionCall("fixture_read", """{"query":"controlled"}""")))
            ))
        }
    }
    val center = ChatStreamCenter(
        store, client, scope = mainScope,
        agentRunner = AgentEngine(model, tools, AgentPromptBuilder(store.attachments))
    )
    val uiOwner = ViewModelStore()
    val chatOwner = ViewModelStore()
    private val privateContext = object : ContextWrapper(context.applicationContext) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(root, "no-backup")
        override fun getCacheDir() = File(root, "cache")
    }
    lateinit var drafts: ChatDraftsViewModel
    lateinit var models: SessionListViewModel
    lateinit var files: LibraryFilesViewModel
    var createdChatViews = 0
        private set
    val publicSettings = ToolSettingsState(
        loading = false,
        snapshot = ToolSettingsSnapshot(
            web = WebToolSettings(searchEnabled = false, pageReaderEnabled = false),
            servers = listOf(McpServerSettings(
                "0123456789abcdef", 1, "受控工具", "https://example.com/controller-fixture", enabled = true,
                enabledTools = setOf("fixture_read")
            ))
        )
    )

    suspend fun initialize(enabled: Boolean) {
        auth.bootstrap()
        client.modelCatalog.refresh(force = true)
        check(client.modelCatalog.state.value.models.any { it.id == "fixture-model" })
        store.save(Session(
            id, "受控 Agent 控制器", "fixture-model",
            agentSettings = AgentSessionSettings(
                enabled = enabled,
                limits = AgentLimits(maxDurationMillis = 600_000, approvalTimeoutMillis = 300_000)
            )
        ))
    }

    fun createUiOwners() {
        drafts = ViewModelProvider(uiOwner, SimpleVMFactory { ChatDraftsViewModel(privateContext) })[ChatDraftsViewModel::class.java]
        models = ViewModelProvider(uiOwner, SimpleVMFactory {
            SessionListViewModel(store, client, auth, center)
        })[SessionListViewModel::class.java]
        files = ViewModelProvider(uiOwner, SimpleVMFactory {
            LibraryFilesViewModel(privateContext, store, drafts::discard)
        })[LibraryFilesViewModel::class.java]
    }

    fun newChatView(): ChatViewModel {
        createdChatViews++
        return ViewModelProvider(chatOwner, SimpleVMFactory { ChatViewModel(id, center) })[ChatViewModel::class.java]
    }

    suspend fun persistedSession(): Session = withContext(Dispatchers.IO) {
        json.decodeFromString<Session>(File(paths.sessions, "$id.json").readText())
    }

    suspend fun persistedDraft() = ChatDraftStore(File(privateContext.noBackupFilesDir, "ui-drafts")).load(id)

    fun close(): List<Job> {
        val jobs = listOf(drafts, models, files).mapNotNull { it.viewModelScope.coroutineContext[Job] }
        chatOwner.clear()
        uiOwner.clear()
        center.close()
        client.modelCatalog.close()
        store.close()
        provider.client.connectionPool.evictAll()
        provider.client.dispatcher.executorService.shutdown()
        return jobs + mainJob + ioJob
    }
}

internal class ControllerTool : AgentToolExecutor {
    override val revision = MutableStateFlow(1L)
    val invocations = CopyOnWriteArrayList<AgentToolInvocation>()
    private val schema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
        put("additionalProperties", false)
    }
    private fun descriptor() = AgentToolDescriptor(
        AgentToolIdentity("0123456789abcdef", revision.value, "fixture_read", "fixture-definition"),
        "fixture_read", "受控测试工具，不连接真实服务", schema, "https://example.com/controller-fixture",
        AgentToolKind.MCP
    )
    override suspend fun snapshot() = AgentToolSnapshot(revision.value, listOf(descriptor()))
    override fun isCurrent(identity: AgentToolIdentity) = identity == descriptor().identity
    override suspend fun validate(invocation: AgentToolInvocation): AgentToolValidation {
        if (!isCurrent(invocation.tool.identity)) throw AgentToolConfigurationChangedException()
        if (invocation.arguments.keys != setOf("query") || invocation.arguments["query"]?.jsonPrimitive?.isString != true) {
            throw AgentToolException("受控参数校验拒绝")
        }
        return AgentToolValidation(invocation.arguments)
    }
    override suspend fun execute(invocation: AgentToolInvocation): AgentToolResult {
        validate(invocation)
        invocations += invocation
        return AgentToolResult(
            "受控工具的实际测试返回",
            sources = listOf(SourceReference(
                "https://example.com/controller-source", "受控工具返回的来源", SourceKind.TOOL_RESOURCE
            ))
        )
    }
    fun invalidate() { revision.value++ }
}
