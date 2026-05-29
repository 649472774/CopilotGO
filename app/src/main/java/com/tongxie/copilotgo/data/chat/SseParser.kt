package com.tongxie.copilotgo.data.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.BufferedSource

/**
 * 解析 OpenAI 风格 SSE：
 *   data: {...json...}
 *   data: [DONE]
 *
 * 输出每条 data line 的字符串，遇到 [DONE] 结束。
 */
object SseParser {
    fun lines(source: BufferedSource): Flow<String> = flow {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) continue
            val trimmed = line.trim()
            if (trimmed.startsWith("data:")) {
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") return@flow
                if (data.isNotEmpty()) emit(data)
            }
        }
    }
}
