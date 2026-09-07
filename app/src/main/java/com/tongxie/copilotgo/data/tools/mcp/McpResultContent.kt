package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.agent.SourceReference
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.boundToolText
import com.tongxie.copilotgo.data.tools.net.ToolUrlGuard
import com.tongxie.copilotgo.data.tools.schema.ValidatedToolSchema
import com.tongxie.copilotgo.data.tools.toolFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException

internal data class McpCallContent(
    val text: String,
    val isError: Boolean,
    val sources: List<SourceReference>,
    val truncated: Boolean
)

internal object McpResultContent {
    fun parse(
        result: JsonObject,
        outputSchema: ValidatedToolSchema?,
        redact: (String) -> String
    ): McpCallContent {
        val declaredError = if ("isError" in result) {
            (result["isError"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 工具错误标志类型无效")
        } else {
            false
        }
        if (!declaredError && outputSchema != null) {
            val structured = result["structuredContent"]
                ?: toolFailure(ToolProblemCode.SCHEMA, "MCP 工具声明了输出 Schema，却没有返回结构化结果")
            outputSchema.validate(structured)
        }
        val blocks = if ("content" in result) {
            result["content"] as? JsonArray
                ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 工具内容必须是数组")
        } else if ("structuredContent" in result) {
            JsonArray(emptyList())
        } else {
            toolFailure(ToolProblemCode.PROTOCOL, "MCP 工具没有返回内容")
        }
        if (blocks.size > 64) toolFailure(ToolProblemCode.TOO_LARGE, "MCP 内容块数量超过限制")
        val parts = mutableListOf<String>()
        val sources = mutableListOf<SourceReference>()
        var unsupported = false
        blocks.forEach { item ->
            val block = item as? JsonObject ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 内容块格式无效")
            when (block.string("type")) {
                "text" -> parts += block.string("text")
                    ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 文本内容缺少文本")
                "resource_link" -> {
                    val source = resourceSource(block, redact)
                    if (source == null) {
                        unsupported = true
                        parts += "工具返回的资源网址不属于受支持的公开 HTTPS 链接，未打开或引用。"
                    } else {
                        sources += source
                        parts += "${source.title}\n${source.url}"
                    }
                }
                "resource" -> {
                    val resource = block["resource"] as? JsonObject
                        ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 嵌入资源格式无效")
                    if ("blob" in resource || resource.string("text") == null) {
                        unsupported = true
                        parts += "工具返回了不支持的二进制资源，未解码或打开。"
                    } else {
                        val text = checkNotNull(resource.string("text"))
                        parts += text
                        resourceSource(resource, redact)?.let {
                            sources += it.copy(excerpt = boundToolText(redact(text), 512).text)
                        }
                    }
                }
                "image", "audio" -> {
                    unsupported = true
                    parts += "工具返回了当前不支持的图片或音频内容，未解码或打开。"
                }
                else -> toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "MCP 工具返回了尚未支持的内容类型")
            }
        }
        if (parts.isEmpty() && "structuredContent" in result) {
            parts += checkNotNull(result["structuredContent"]).toString()
        }
        if (parts.isEmpty()) parts += "服务已返回完成结果，但没有可显示的文本内容。"
        val text = boundToolText(redact(parts.joinToString("\n\n")), MAX_TEXT_BYTES)
        return McpCallContent(
            text.text, declaredError || unsupported, sources.distinctBy { it.url }.take(32), text.truncated
        )
    }

    private fun resourceSource(value: JsonObject, redact: (String) -> String): SourceReference? {
        val uri = value.string("uri") ?: return null
        if (redact(uri) != uri) return null
        val url = try {
            ToolUrlGuard.parse(uri)
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return SourceReference(
            url.toString(), redact(value.string("name") ?: value.string("title") ?: url.host).take(160),
            SourceKind.TOOL_RESOURCE
        )
    }

    private const val MAX_TEXT_BYTES = 128 * 1024
}
