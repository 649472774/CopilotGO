package com.tongxie.copilotgo.data.tools.web

import com.tongxie.copilotgo.data.agent.AgentToolResult
import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.agent.SourceReference
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.boundToolText
import com.tongxie.copilotgo.data.tools.net.ToolUrlGuard
import com.tongxie.copilotgo.data.tools.toolFailure
import java.io.IOException

internal object ExaSearchParser {
    fun parse(text: String, maxResults: Int, upstreamTruncated: Boolean = false): AgentToolResult {
        if (maxResults !in 1..5) toolFailure(ToolProblemCode.SCHEMA, "搜索结果数量必须在 1 至 5 之间")
        val records = mutableListOf<Record>()
        var pending: Record? = null
        var metadataLines = 0
        fun finish() {
            pending?.takeIf { it.url != null }?.let { records += it }
            pending = null
        }
        text.lineSequence().forEach { original ->
            val line = original.removeSuffix("\r")
            when {
                line.startsWith("Title:") -> {
                    finish()
                    if (records.size >= 128) toolFailure(ToolProblemCode.TOO_LARGE, "搜索服务返回了过多结果记录")
                    val title = line.removePrefix("Title:").trim()
                    pending = title.takeIf { it.isNotBlank() }?.let { Record(it.take(240)) }
                    metadataLines = 0
                }
                pending?.url == null -> {
                    val current = pending ?: return@forEach
                    metadataLines++
                    when {
                        metadataLines > 5 -> pending = null
                        line.startsWith("URL:") -> {
                            val candidate = line.removePrefix("URL:").trim()
                            if (candidate.any(Char::isWhitespace) || "[redacted]" in candidate) {
                                pending = null
                            } else {
                                current.url = publicUrl(candidate)
                                if (current.url == null) pending = null
                            }
                        }
                        line.isBlank() || line.startsWith("Published Date:") ||
                            line.startsWith("Author:") || line.startsWith("ID:") -> Unit
                        else -> pending = null
                    }
                }
                else -> {
                    val current = checkNotNull(pending)
                    if (current.excerpt.length < 2400) {
                        if (current.excerpt.isNotEmpty()) current.excerpt.append('\n')
                        current.excerpt.append(line.removePrefix("Text:").trim().take(2400 - current.excerpt.length))
                    }
                }
            }
        }
        finish()
        val results = records.distinctBy { it.url }.take(maxResults)
        if (results.isEmpty()) {
            toolFailure(
                ToolProblemCode.PROTOCOL,
                "Exa 没有返回可识别且可引用的公开 HTTPS 搜索结果；未生成任何替代链接，请重试或更换搜索词"
            )
        }
        val sources = results.map {
            SourceReference(
                checkNotNull(it.url), it.title, SourceKind.SEARCH_HIT,
                excerpt = boundToolText(it.excerpt.toString().trim(), 1600).text.takeIf(String::isNotEmpty)
            )
        }
        val content = buildString {
            append("Exa 搜索结果：以下为搜索提供方返回的第三方摘录，尚未读取或验证原网页。\n")
            sources.forEachIndexed { index, source ->
                append("\n${index + 1}. ${source.title}\n来源：${source.url}\n")
                source.excerpt?.let { append("摘录：$it\n") }
            }
        }
        return AgentToolResult(
            content, sources = sources,
            truncated = upstreamTruncated || records.size > results.size || results.any { it.excerpt.length >= 2400 }
        )
    }

    private fun publicUrl(value: String): String? = try {
        ToolUrlGuard.parse(value).toString()
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Record(val title: String, var url: String? = null) {
        val excerpt = StringBuilder()
    }
}
