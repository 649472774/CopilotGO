package com.tongxie.copilotgo.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import okio.BufferedSource
import java.io.EOFException
import java.io.IOException

class StreamProtocolException(message: String) : IOException(message)

data class SseEvent(val event: String, val data: String, val id: String? = null)

object SseParser {
    private const val MAX_EVENT_BYTES = 1024 * 1024

    fun events(source: BufferedSource): Flow<SseEvent> = flow {
        var event = "message"
        var id: String? = null
        val data = StringBuilder()
        var hasData = false
        var firstLine = true
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = readLine(source)
            val line = if (firstLine) read?.removePrefix("\uFEFF") else read
            firstLine = false
            if (line == null || line.isEmpty()) {
                if (hasData || event == "error") {
                    emit(SseEvent(event, data.toString(), id))
                }
                if (line == null) break
                event = "message"
                data.clear()
                hasData = false
                continue
            }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "event" -> event = value.ifEmpty { "message" }
                "id" -> if ('\u0000' !in value) id = value
                "data" -> {
                    if (!hasData && value == "[DONE]") {
                        emit(SseEvent(event, value, id))
                        return@flow
                    }
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                    if (data.length > MAX_EVENT_BYTES) {
                        throw StreamProtocolException("流式事件超过大小限制")
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Compatibility adapter; clients that need error and completion semantics use events(). */
    fun lines(source: BufferedSource): Flow<String> =
        events(source).takeWhile { it.data != "[DONE]" }.map { it.data }

    private fun readLine(source: BufferedSource): String? = try {
        source.readUtf8LineStrict(MAX_EVENT_BYTES.toLong())
    } catch (_: EOFException) {
        if (source.buffer.size > MAX_EVENT_BYTES) {
            throw StreamProtocolException("流式事件超过大小限制")
        }
        if (!source.exhausted()) throw StreamProtocolException("流式事件缺少换行")
        if (source.buffer.size == 0L) null else source.readUtf8().removeSuffix("\r")
    }
}
