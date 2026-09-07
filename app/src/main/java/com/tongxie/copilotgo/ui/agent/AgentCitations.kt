package com.tongxie.copilotgo.ui.agent

import com.tongxie.copilotgo.data.agent.SourceReference
import com.tongxie.copilotgo.data.chat.UiMessage

private val sourceId = Regex("S[1-9][0-9]{0,8}")
internal fun isAgentSourceId(value: String): Boolean = value.matches(sourceId)

internal fun agentCitationLinks(messages: List<UiMessage>, messageId: String): Map<String, String> {
    val sources = ArrayDeque<SourceReference>()
    var foundMessage = false
    for (message in messages) {
        for (source in message.agentRun?.sources.orEmpty().takeLast(512)) {
            if (sources.size == 512) sources.removeFirst()
            sources.addLast(source)
        }
        if (message.id == messageId) {
            foundMessage = true
            break
        }
    }
    if (!foundMessage) return emptyMap()
    val links = LinkedHashMap<String, String>()
    val conflicts = mutableSetOf<String>()
    for (source in sources) {
        if (!isAgentSourceId(source.id) || source.id in conflicts) continue
        val destination = agentSourceDestination(source.url) ?: continue
        val previous = links[source.id]
        if (previous != null && previous != destination) {
            links.remove(source.id)
            conflicts.add(source.id)
        } else links[source.id] = destination
    }
    return links
}
