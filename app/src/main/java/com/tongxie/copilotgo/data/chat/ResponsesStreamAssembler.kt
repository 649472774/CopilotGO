package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentFunctionCall
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.agent.AgentToolCall
import com.tongxie.copilotgo.data.net.apiFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Validates the complete Responses output before allowing any proposed function to execute. */
internal class ResponsesStreamAssembler(
    private val json: Json,
    private val allowTools: Boolean = true
) {
    private val items = sortedMapOf<Int, Item>()
    private val itemIndexes = HashMap<String, Int>()
    private var eventCount = 0
    private var streamBytes = 0L
    private var argumentBytes = 0
    private var characters = 0
    private var lastTextPosition = -1
    private var responseCreated = false
    private var lastSequence = -1
    private var terminal: AgentStreamEvent.Completed? = null
    private var delivered = false
    private var failed = false
    private var done = false
    val isDone: Boolean get() = done
    var outputItems: List<JsonObject> = emptyList()
        private set

    fun accept(event: SseEvent): List<AgentStreamEvent.TextDelta> {
        try {
            agentCheck(!failed && !done && !delivered, "Responses 完成后收到重复事件")
            agentCheck(++eventCount <= AgentWireLimits.MAX_EVENTS, "Responses 流式事件数量超过限制")
            streamBytes += agentUtf8Size(event.data, AgentWireLimits.MAX_EVENT_BYTES)
            streamBytes += agentUtf8Size(event.event, 256)
            event.id?.let { streamBytes += agentUtf8Size(it, 1024) }
            agentCheck(streamBytes <= AgentWireLimits.MAX_STREAM_BYTES, "Responses 流式数据总量超过限制")
            if (event.event == "error") throw apiFailure(null, event.data, json)
            if (event.data == "[DONE]") {
                agentCheck(event.event == "message" && terminal != null, "Responses 回复缺少成功完成状态")
                done = true
                return emptyList()
            }
            if (event.data.isBlank()) return emptyList()
            if (event.event in setOf("ping", "heartbeat")) {
                if (event.data.trimStart().startsWith('{')) {
                    checkError(AgentJsonGuard.objectValue(event.data, AgentWireLimits.MAX_EVENT_BYTES))
                }
                return emptyList()
            }
            val root = AgentJsonGuard.objectValue(event.data, AgentWireLimits.MAX_EVENT_BYTES)
            checkError(root)
            root["sequence_number"]?.let { value ->
                val sequence = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                agentCheck(sequence != null && sequence >= 0 && sequence > lastSequence,
                    "Responses 事件序号无效或发生重复、倒序")
                lastSequence = requireNotNull(sequence)
            }
            val type = root.string("type") ?: event.event
            agentCheck(event.event == "message" || event.event == type, "Responses 事件名称与内容不一致")
            agentCheck(terminal == null, "Responses 成功状态后仍有增量或重复完成事件")
            val deltas = mutableListOf<AgentStreamEvent.TextDelta>()
            when (type) {
                "error" -> throw apiFailure(null, event.data, json)
                "response.created", "response.in_progress", "response.queued" -> {
                    if (type == "response.created") {
                        agentCheck(!responseCreated && items.isEmpty(), "Responses 回复开始事件重复或顺序无效")
                        responseCreated = true
                    }
                    val response = root.objectField("response")
                    validateResponse(response)
                    agentCheck(response.string("status") in setOf("queued", "in_progress"),
                        "Responses 开始事件状态无效")
                }
                "response.failed" -> {
                    val response = root.objectField("response")
                    validateResponse(response)
                    throw apiFailure(null, response.toString(), json)
                }
                "response.incomplete" -> {
                    val response = root.objectField("response")
                    validateResponse(response)
                    val reason = (response["incomplete_details"] as? JsonObject)?.string("reason")
                    if (reason == "content_filter") throw apiFailure(null, """{"code":"content_filter"}""", json)
                    throw StreamProtocolException(
                        if (reason == "max_output_tokens") "回复达到模型输出限制，内容尚未完成；不会执行未完成的工具调用"
                        else "Responses 回复未完成，请重试"
                    )
                }
                "response.output_item.added" -> {
                    val index = root.index("output_index")
                    agentCheck(index !in items, "Responses 输出项重复")
                    bindItem(index, root.objectField("item"), complete = false, deltas = deltas)
                }
                "response.output_item.done" ->
                    bindItem(root.index("output_index"), root.objectField("item"), complete = true, deltas = deltas)
                "response.content_part.added", "response.content_part.done" -> {
                    val (index, item) = eventItem(root, "message")
                    val contentIndex = root.index("content_index")
                    val part = root.objectField("part")
                    val text = partText(part)
                    item.text(contentIndex).complete(
                        text, index, contentIndex, type.endsWith(".done"), deltas
                    )
                }
                "response.output_text.delta", "response.output_text.done" -> {
                    val (index, item) = eventItem(root, "message")
                    val contentIndex = root.index("content_index")
                    val text = item.text(contentIndex)
                    if (type.endsWith(".delta")) {
                        text.append(root.requiredString("delta"), index, contentIndex, deltas)
                    } else {
                        text.complete(root.requiredString("text"), index, contentIndex, true, deltas)
                    }
                }
                "response.function_call_arguments.delta", "response.function_call_arguments.done" -> {
                    val (_, item) = eventItem(root, "function_call")
                    if (type.endsWith(".delta")) {
                        agentCheck(!item.argumentsDone, "Responses 工具参数结束后仍有增量")
                        item.appendArguments(root.requiredString("delta"))
                    } else {
                        item.completeArguments(root.requiredString("arguments"))
                    }
                }
                "response.refusal.delta", "response.refusal.done" ->
                    throw apiFailure(null, """{"code":"content_filter"}""", json)
                "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
                "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
                "response.reasoning_text.delta", "response.reasoning_text.done" ->
                    eventItem(root, "reasoning")
                "response.output_text.annotation.added" -> eventItem(root, "message")
                "response.completed" -> {
                    val response = root.objectField("response")
                    validateResponse(response)
                    agentCheck(response.string("status") == "completed", "Responses 结束事件未确认成功")
                    checkError(response)
                    val output = response["output"] as? JsonArray
                        ?: throw StreamProtocolException("Responses 成功事件缺少输出")
                    agentCheck(output.size in 1..MAX_ITEMS, "Responses 输出项数量无效")
                    output.forEachIndexed { index, element ->
                        bindItem(index, element as? JsonObject
                            ?: throw StreamProtocolException("Responses 输出格式无效"),
                            complete = true, finalResponse = true, deltas = deltas)
                    }
                    agentCheck(items.keys.toList() == output.indices.toList(), "Responses 结束事件遗漏输出项")
                    val calls = items.values.filter { it.kind == "function_call" }.map { it.toolCall() }
                    agentCheck(calls.size <= AgentWireLimits.MAX_TOOLS, "Responses 工具调用数量超过限制")
                    agentCheck(calls.map { it.id }.distinct().size == calls.size, "Responses 工具调用标识重复")
                    agentCheck(calls.isNotEmpty() || items.values.any { item ->
                        item.texts.values.any { it.value.any { char -> !char.isWhitespace() } }
                    }, "Responses 未返回有效回复或工具调用")
                    // Replay whole terminal items so each opaque ID and ciphertext stay paired.
                    outputItems = ResponsesOutputGuard.snapshot(
                        output.map { it as JsonObject },
                        items.values.joinToString("") { item -> item.texts.values.joinToString("") { it.value } },
                        calls
                    )
                    terminal = AgentStreamEvent.Completed(if (calls.isEmpty()) "stop" else "tool_calls", calls)
                }
                else -> throw StreamProtocolException("Responses 流式事件类型不受支持")
            }
            return deltas
        } catch (error: Exception) {
            failed = true
            outputItems = emptyList()
            terminal = null
            throw error
        }
    }

    fun endOfStream(): AgentStreamEvent.Completed {
        agentCheck(!failed && !delivered, "Responses 回复已失败或重复完成")
        delivered = true
        val completed = terminal ?: throw StreamProtocolException("Responses 回复在成功完成前中断，请重试")
        return completed.copy(responsesOutput = outputItems)
    }

    private fun validateResponse(response: JsonObject) {
        // Copilot re-encrypts response/item IDs per event; they are not stream correlation keys.
        agentResponseId(response.requiredString("id"))
        checkError(response)
    }

    private fun bindItemId(id: String, index: Int) {
        agentOutputItemId(id)
        // Remember aliases within this bounded HTTP stream, but never let one cross output indexes.
        val previous = itemIndexes.putIfAbsent(id, index)
        agentCheck(previous == null || previous == index, "Responses 输出项标识与索引不一致")
    }

    private fun checkError(root: JsonObject) {
        if ((root["error"] != null && root["error"] != JsonNull) || root.string("type") == "error") {
            throw apiFailure(null, root.toString(), json)
        }
    }

    private fun eventItem(root: JsonObject, kind: String): Pair<Int, Item> {
        val index = root.index("output_index")
        val item = items[index] ?: throw StreamProtocolException("Responses 增量缺少输出项标识")
        agentCheck(item.kind == kind && !item.finished, "Responses 增量对应的输出类型或状态不一致")
        bindItemId(root.requiredString("item_id"), index)
        return index to item
    }

    private fun bindItem(
        index: Int,
        value: JsonObject,
        complete: Boolean,
        finalResponse: Boolean = false,
        deltas: MutableList<AgentStreamEvent.TextDelta>
    ) {
        val kind = value.requiredString("type")
        agentCheck(kind in setOf("message", "function_call", "reasoning"), "Responses 输出类型不受支持")
        val id = value.requiredString("id")
        bindItemId(id, index)
        val item = items.getOrPut(index) { Item(kind) }
        agentCheck(item.kind == kind, "Responses 输出项类型发生变化")
        agentCheck(!item.finished || finalResponse, "Responses 输出项重复完成")
        val phase = value.string("phase")
        if (item.finished) agentCheck(item.phase == phase, "Responses 已完成输出的阶段标识发生变化")
        if (complete) item.phase = phase
        if (complete) {
            agentCheck(value.string("status") in setOf(null, "completed"), "Responses 输出项尚未完成")
        }
        when (kind) {
            "message" -> {
                agentCheck(value.string("role") == "assistant", "Responses 输出消息角色无效")
                val content = value["content"] as? JsonArray
                    ?: throw StreamProtocolException("Responses 输出消息缺少内容")
                agentCheck(content.size <= MAX_ITEMS, "Responses 消息内容项过多")
                content.forEachIndexed { contentIndex, element ->
                    val part = element as? JsonObject ?: throw StreamProtocolException("Responses 内容格式无效")
                    item.text(contentIndex).complete(partText(part), index, contentIndex, complete, deltas)
                }
                if (complete) agentCheck(item.texts.keys.toList() == content.indices.toList(), "Responses 结束消息遗漏内容")
            }
            "function_call" -> {
                agentCheck(allowTools, "当前普通聊天模式不支持模型请求的工具调用")
                agentCheck(items.values.count { it.kind == "function_call" } <= AgentWireLimits.MAX_TOOLS,
                    "Responses 工具调用数量超过限制")
                val callId = value.requiredString("call_id")
                val name = value.requiredString("name")
                agentToolCallId(callId)
                agentToolName(name)
                agentCheck(item.callId == null || item.callId == callId, "Responses call_id 发生变化")
                agentCheck(item.name == null || item.name == name, "Responses 工具名称发生变化")
                item.callId = callId
                item.name = name
                val arguments = value.requiredString("arguments")
                if (complete) item.completeArguments(arguments) else item.appendArguments(arguments)
            }
            "reasoning" -> if (complete) {
                val summary = value["summary"] as? JsonArray
                    ?: throw StreamProtocolException("Responses 推理输出缺少 summary")
                // Ciphertext is opaque and may be re-encrypted between complete snapshots.
                value.string("encrypted_content")
                if (item.finished) {
                    agentCheck(item.reasoningSummary == summary, "Responses 已完成的推理摘要发生变化")
                }
                item.reasoningSummary = summary
            }
        }
        if (complete) item.finished = true
    }

    private fun partText(part: JsonObject): String = when (part.requiredString("type")) {
        "output_text" -> part.requiredString("text")
        "refusal" -> throw apiFailure(null, """{"code":"content_filter"}""", json)
        else -> throw StreamProtocolException("Responses 消息内容类型不受支持")
    }

    private inner class Item(val kind: String) {
        var finished = false
        val texts = sortedMapOf<Int, Text>()
        var callId: String? = null
        var name: String? = null
        var phase: String? = null
        var reasoningSummary: JsonArray? = null
        private val arguments = StringBuilder()
        private var bytes = 0
        var argumentsDone = false
        fun text(index: Int): Text = texts.getOrPut(index) { Text() }

        fun appendArguments(part: String) {
            val size = agentUtf8Size(part, AgentWireLimits.MAX_ARGUMENT_BYTES)
            agentCheck(size <= AgentWireLimits.MAX_ARGUMENT_BYTES - bytes, "Responses 单个工具参数超过限制")
            agentCheck(size <= AgentWireLimits.MAX_TOTAL_ARGUMENT_BYTES - argumentBytes, "Responses 工具参数总量超过限制")
            bytes += size
            argumentBytes += size
            arguments.append(part)
        }

        fun completeArguments(full: String) {
            agentUtf8Size(full, AgentWireLimits.MAX_ARGUMENT_BYTES)
            agentCheck(full.startsWith(arguments.toString()) && (!argumentsDone || full == arguments.toString()),
                "Responses 工具参数增量与完整参数不一致")
            appendArguments(full.substring(arguments.length))
            AgentJsonGuard.objectValue(full, AgentWireLimits.MAX_ARGUMENT_BYTES)
            argumentsDone = true
        }

        fun toolCall(): AgentToolCall {
            agentCheck(finished && argumentsDone, "Responses 工具调用未完成")
            return AgentToolCall(requireNotNull(callId), AgentFunctionCall(requireNotNull(name), arguments.toString()))
        }
    }

    private inner class Text {
        val value = StringBuilder()
        private var finished = false

        fun append(part: String, index: Int, contentIndex: Int, deltas: MutableList<AgentStreamEvent.TextDelta>) {
            agentCheck(!finished, "Responses 文字结束后仍有增量")
            if (part.isEmpty()) return
            val position = index * MAX_ITEMS + contentIndex
            agentCheck(position >= lastTextPosition, "Responses 文字增量顺序不一致")
            lastTextPosition = position
            agentCheck(part.length <= Constants.MAX_RESPONSE_CHARACTERS - characters, "回复超过本地安全长度限制")
            characters += part.length
            value.append(part)
            deltas.add(AgentStreamEvent.TextDelta(part))
        }

        fun complete(
            full: String,
            index: Int,
            contentIndex: Int,
            complete: Boolean,
            deltas: MutableList<AgentStreamEvent.TextDelta>
        ) {
            agentCheck(full.startsWith(value.toString()) && (!finished || full == value.toString()),
                "Responses 文字增量与完整输出不一致")
            if (full.length > value.length) append(full.substring(value.length), index, contentIndex, deltas)
            if (complete) finished = true
        }
    }

    private fun JsonObject.objectField(key: String): JsonObject =
        this[key] as? JsonObject ?: throw StreamProtocolException("Responses 缺少 $key 对象")

    private fun JsonObject.index(key: String): Int {
        val value = this[key] as? JsonPrimitive
        val index = value?.takeUnless { it.isString }?.intOrNull
        agentCheck(index != null && index in 0 until MAX_ITEMS, "Responses 输出索引无效")
        return requireNotNull(index)
    }

    companion object {
        private const val MAX_ITEMS = ResponsesOutputGuard.MAX_ITEMS
    }
}
