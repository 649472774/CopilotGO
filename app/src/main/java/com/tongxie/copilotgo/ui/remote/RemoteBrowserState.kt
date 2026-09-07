package com.tongxie.copilotgo.ui.remote

import com.tongxie.copilotgo.data.Constants

internal enum class RemoteLoadPhase { IDLE, LOADING, READY, FAILED, INTERRUPTED, RELEASED }

internal data class RemoteProblem(val title: String, val detail: String)

internal data class RemotePageState(
    val url: String = Constants.REMOTE_HOME_URL,
    val committedUrl: String? = null,
    val phase: RemoteLoadPhase = RemoteLoadPhase.IDLE,
    val progress: Int = 0,
    val hasVisiblePage: Boolean = false,
    val canGoBack: Boolean = false,
    val problem: RemoteProblem? = null,
    val committedForLoad: Boolean = false,
    val finishBeforeCommit: Boolean = false
) {
    fun started(url: String) = copy(
        url = url,
        phase = RemoteLoadPhase.LOADING,
        progress = 0,
        problem = null,
        committedForLoad = false,
        finishBeforeCommit = false
    )

    fun frameStarted(url: String) = if (phase == RemoteLoadPhase.FAILED || phase == RemoteLoadPhase.INTERRUPTED) this
    else started(url)

    fun progressed(value: Int) = if (phase == RemoteLoadPhase.LOADING) {
        copy(progress = value.coerceIn(0, 100))
    } else this

    fun committed(url: String) = if (phase == RemoteLoadPhase.LOADING && this.url == url) {
        copy(
            committedUrl = url,
            hasVisiblePage = true,
            committedForLoad = true,
            phase = if (finishBeforeCommit) RemoteLoadPhase.READY else phase,
            progress = if (finishBeforeCommit) 100 else progress
        )
    } else this

    // WebView can deliver onPageFinished after errors or stopLoading().
    fun finished(url: String) = if (phase == RemoteLoadPhase.LOADING && this.url == url) {
        if (committedForLoad) copy(phase = RemoteLoadPhase.READY, progress = 100)
        else copy(finishBeforeCommit = true)
    } else this

    fun failed(problem: RemoteProblem) = copy(
        phase = RemoteLoadPhase.FAILED,
        problem = problem
    )

    fun failedFor(url: String, problem: RemoteProblem) = copy(url = url).failed(problem)

    fun interrupted() = copy(
        phase = RemoteLoadPhase.INTERRUPTED,
        problem = RemoteProblem("已停止加载", "网页尚未加载完成。可以重新加载，或返回会话列表。")
    )

    fun released(problem: RemoteProblem) = copy(
        phase = RemoteLoadPhase.RELEASED,
        hasVisiblePage = false,
        canGoBack = false,
        problem = problem
    )
}

internal enum class RemoteNetworkMode { SYSTEM, APP_PROXY }

internal data class RemotePreferences(
    val desktop: Boolean = false,
    val immersive: Boolean = true,
    val networkMode: RemoteNetworkMode = RemoteNetworkMode.SYSTEM
)

internal data class RemoteExternalRequest(
    val url: String,
    val origin: String,
    val download: Boolean = false
)

internal data class RemoteNotice(val id: Long, val message: String)

internal data class RemoteBrowserState(
    val page: RemotePageState = RemotePageState(),
    val preferences: RemotePreferences = RemotePreferences(),
    val preferencesReady: Boolean = false,
    val preferencesProblem: RemoteProblem? = null,
    val savingPreferences: Boolean = false,
    val transportReady: Boolean = false,
    val transportLabel: String = "正在读取网络设置",
    val transportProblem: RemoteProblem? = null,
    val clearingCookies: Boolean = false,
    val cookieProblem: RemoteProblem? = null,
    val externalRequest: RemoteExternalRequest? = null,
    val notice: RemoteNotice? = null
) {
    val problem: RemoteProblem?
        get() = preferencesProblem ?: cookieProblem ?: transportProblem ?: page.problem
}
