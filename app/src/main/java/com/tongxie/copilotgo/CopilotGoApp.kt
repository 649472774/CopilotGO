package com.tongxie.copilotgo

import android.app.Application
import com.tongxie.copilotgo.data.agent.AgentEngine
import com.tongxie.copilotgo.data.agent.AgentModelTransport
import com.tongxie.copilotgo.data.agent.AgentPromptBuilder
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.CopilotTokenClient
import com.tongxie.copilotgo.data.auth.DeviceFlowClient
import com.tongxie.copilotgo.data.auth.TokenStore
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.CopilotChatClient
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.net.ProxyAwareHttpClientProvider
import com.tongxie.copilotgo.data.proxy.ProxyHealthChecker
import com.tongxie.copilotgo.data.proxy.ProxySettingsStore
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.CredentialVault
import com.tongxie.copilotgo.data.storage.SessionStore
import com.tongxie.copilotgo.data.tools.ConfiguredToolExecutor
import com.tongxie.copilotgo.data.tools.ToolSettingsStore
import com.tongxie.copilotgo.data.tools.mcp.RemoteMcpService
import com.tongxie.copilotgo.data.tools.net.ToolHttpClient
import com.tongxie.copilotgo.data.tools.web.WebToolService
import com.tongxie.copilotgo.data.update.UpdateChecker
import com.tongxie.copilotgo.data.update.UpdatePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit

class CopilotGoApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 最简手写 DI 容器 */
class AppContainer(app: CopilotGoApp) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        isLenient = true
    }

    private val logger = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.HEADERS
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        // Credentials must not be included in HTTP logs.
        redactHeader("Authorization")
        redactHeader("authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
        redactHeader("X-GitHub-Api-Version")
        redactHeader("Proxy-Authorization")
    }

    val proxySettings = ProxySettingsStore(app)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpProvider: HttpClientProvider = ProxyAwareHttpClientProvider(
        baseBuilder = {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(logger)
        },
        proxyConfigFlow = proxySettings.config,
        scope = appScope,
        readiness = proxySettings.initialized,
        configurationError = proxySettings.loadError
    )

    val tokenStore = TokenStore(app)

    private val deviceFlow = DeviceFlowClient(httpProvider, json)
    private val copilotToken = CopilotTokenClient(httpProvider, json)

    val authRepo = AuthRepository(tokenStore, deviceFlow, copilotToken)

    val healthChecker = ProxyHealthChecker(httpProvider, authRepo)

    val chatClient = CopilotChatClient(
        httpProvider,
        json,
        authRepo,
        modelCacheFile = File(app.filesDir, "model-catalog.json")
    )
    val modelCatalog = chatClient.modelCatalog

    val paths = AppPaths(app)
    val sessionStore = SessionStore(paths, json)

    val toolHttp = ToolHttpClient(httpProvider)
    val toolSettings = ToolSettingsStore(CredentialVault(app), appScope)
    val remoteMcp = RemoteMcpService(toolSettings, toolHttp, appScope)
    val webTools = WebToolService(toolSettings, toolHttp, remoteMcp)
    val toolExecutor = ConfiguredToolExecutor(toolSettings, remoteMcp, webTools, appScope)
    private val agentEngine = AgentEngine(
        AgentModelTransport(chatClient::streamAgentChat),
        toolExecutor,
        AgentPromptBuilder(sessionStore.attachments),
        json
    )

    val appContext: android.content.Context = app.applicationContext

    val updatePrefs = UpdatePrefs(app)
    val updateChecker = UpdateChecker(
        httpProvider = httpProvider,
        json = json,
        currentVersionName = BuildConfig.VERSION_NAME,
        debugPackage = BuildConfig.DEBUG
    )

    /** Application-owned streaming; the session store remains the live state authority. */
    val chatStreamCenter = ChatStreamCenter(sessionStore, chatClient, agentRunner = agentEngine)
}
