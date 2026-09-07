package com.tongxie.copilotgo.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double = 0.1,
    @SerialName("top_p") val topP: Double = 1.0,
    val n: Int = 1
)

// ---- Vision (multi-modal) message types ----
@Serializable
data class VisionContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: VisionImageUrl? = null
)

@Serializable
data class VisionImageUrl(
    val url: String,
    val detail: String = "auto"
)

@Serializable
data class VisionMessage(
    val role: String,
    val content: List<VisionContentPart>
)

@Serializable
data class VisionRequest(
    val model: String,
    val messages: List<VisionMessage>,
    val stream: Boolean = true,
    val temperature: Double = 0.1,
    @SerialName("top_p") val topP: Double = 1.0,
    val n: Int = 1
)

@Serializable
data class ChatStreamChunk(
    val id: String? = null,
    val choices: List<ChatStreamChoice> = emptyList(),
    val model: String? = null
)

@Serializable
data class ChatStreamChoice(
    val index: Int = 0,
    val delta: DeltaContent? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class DeltaContent(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class ModelListResponse(
    val data: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String? = null,
    @SerialName("model_picker_enabled") val modelPickerEnabled: Boolean = true,
    val capabilities: ModelCapabilities? = null,
    @SerialName("is_chat_default") val isChatDefault: Boolean = false,
    @SerialName("supported_endpoints") val supportedEndpoints: List<String>? = null,
    val policy: ModelPolicy? = null
) {
    val supportsVision: Boolean get() = capabilities?.supports?.vision == true
    val supportsTools: Boolean get() = capabilities?.supports?.toolCalls == true
    val chatCompatible: Boolean
        get() = id.isNotBlank() && modelPickerEnabled &&
            (capabilities?.type == null || capabilities.type == "chat") &&
            (supportedEndpoints == null || "/chat/completions" in supportedEndpoints) &&
            policy?.state != "disabled"
}

@Serializable
data class ModelCapabilities(
    val family: String? = null,
    val type: String? = null,
    val supports: ModelSupports? = null,
    val limits: ModelLimits? = null
)

@Serializable
data class ModelSupports(
    val vision: Boolean = false,
    @SerialName("tool_calls") val toolCalls: Boolean = false,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean = false
)

@Serializable
data class ModelLimits(
    @SerialName("max_prompt_tokens") val maxPromptTokens: Int? = null,
    @SerialName("max_context_window_tokens") val maxContextWindowTokens: Int? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null
)

@Serializable
data class ModelPolicy(val state: String? = null)

@Serializable
enum class AttachmentKind { IMAGE, TEXT }

@Serializable
data class AttachmentRef(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: AttachmentKind
)

@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val revision: Long,
    val messageCount: Int,
    val preview: String,
    val hasImages: Boolean,
    val loadError: String? = null
)

/** UI 用的会话消息（带本地 id 与时间）
 *  content 必须是 val + 每次产生新对象，否则 StateFlow 的 distinct 去重会吃掉流式增量 */
@Serializable
data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    /** 可选图片附件（base64 data URI 或 http(s) URL），用于视觉模型 */
    val imageUrls: List<String> = emptyList(),
    val attachments: List<AttachmentRef> = emptyList(),
    val finishReason: String? = null,
    val submissionId: String? = null
)

/** UI 用的会话
 *  revision 每次需要触发 UI 重组都自增，保证 data class equals 不会把流式增量去重 */
@Serializable
data class Session(
    val id: String,
    var title: String,
    var model: String,
    val messages: MutableList<UiMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var pinned: Boolean = false,
    var revision: Long = 0
)
