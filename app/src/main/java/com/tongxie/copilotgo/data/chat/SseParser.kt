package com.tongxie.copilotgo.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.BufferedSource

/**
 * 解析 OpenAI 风格 SSE：
 *   data: {...json...}
 *   data: [DONE]
 *
 * 输出每条 data line 的字符串，遇到 [DONE] 结束。
 *
 * 关键：source.exhausted() / readUtf8Line() 是阻塞 IO（等下一帧 HTTP/2 数据）。
 * 必须 .flowOn(Dispatchers.IO)，否则上游会在 collector 的协程里跑——
 * 如果 collector 在 viewModelScope（Main），主线程会被阻塞 → ANR。
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
    }.flowOn(Dispatchers.IO)
}
