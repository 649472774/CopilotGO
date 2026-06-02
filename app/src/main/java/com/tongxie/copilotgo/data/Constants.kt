package com.tongxie.copilotgo.data

object Constants {
    const val CLIENT_ID = "01ab8ac9400c4e429b23"

    const val GITHUB_DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val GITHUB_ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"

    const val COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token"
    const val COPILOT_API_BASE = "https://api.individual.githubcopilot.com"
    const val COPILOT_CHAT_URL = "$COPILOT_API_BASE/chat/completions"
    const val COPILOT_MODELS_URL = "$COPILOT_API_BASE/models"

    const val EDITOR_VERSION = "vscode/1.99.3"
    const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.24.0"
    const val USER_AGENT_VSCODE = "GitHubCopilotChat/0.24.0"
    const val COPILOT_INTEGRATION_ID = "vscode-chat"
    const val OPENAI_INTENT = "conversation-edits"

    const val APP_DATA_DIR_NAME = "CopilotGoData"

    // 应用内更新：从 GitHub Releases 拉取最新版本。owner/repo 与发布仓库一致。
    const val GITHUB_REPO_OWNER = "649472774"
    const val GITHUB_REPO_NAME = "CopilotGO"
    const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    const val GITHUB_RELEASES_PAGE =
        "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

    // Remote 模式：内嵌官方 web Copilot。登录态由 WebView 的 CookieManager 持久化。
    const val REMOTE_HOME_URL = "https://github.com/copilot"
    // 强制桌面 UA（可在 Remote 页切换），避免被路由到阉割版 m.github.com / 拿到完整功能。
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    const val DEFAULT_MODEL = "claude-sonnet-4.5"

    // 注意：这些是"看起来稳定"的真实 model id。实际 picker 会动态从 /models 拉
    // 取真实订阅可用列表覆盖。这里只是登录前/拉取失败时的兜底。
    val FALLBACK_MODELS = listOf(
        "claude-sonnet-4.5",
        "claude-opus-4.6",
        "gpt-4.1",
        "gpt-5.2",
        "gemini-2.5-pro"
    )
}
