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
