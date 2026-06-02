package com.tongxie.copilotgo

import android.app.Application
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
import com.tongxie.copilotgo.data.storage.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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
        // Bug 11 修复：debug 构建用 HEADERS 级日志便于排查，但绝对不能把 Bearer token
        // 打进 logcat — 任何 READ_LOGS 权限的进程或 device dump 都能拿到。
        redactHeader("Authorization")
        redactHeader("authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
        redactHeader("X-GitHub-Api-Version")
        redactHeader("Proxy-Authorization")
    }

    val proxySettings = ProxySettingsStore(app)

    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        scope = providerScope
    )

    val tokenStore = TokenStore(app)

    private val deviceFlow = DeviceFlowClient(httpProvider, json)
    private val copilotToken = CopilotTokenClient(httpProvider, json)

    val authRepo = AuthRepository(tokenStore, deviceFlow, copilotToken)

    val healthChecker = ProxyHealthChecker(httpProvider, authRepo)

    val chatClient = CopilotChatClient(httpProvider, json, authRepo)

    val paths = AppPaths(app)
    val sessionStore = SessionStore(paths, json)

    /** Application 级单例：跨 ChatViewModel 生命周期持有 SSE 流任务。详见类注释 & AGENTS.md §27。 */
    val chatStreamCenter = ChatStreamCenter(sessionStore, chatClient)
}
