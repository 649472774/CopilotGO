package com.tongxie.copilotgo.data.agent

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.IOException

@Serializable
enum class AgentToolKind { PUBLIC_WEB_SEARCH, PUBLIC_WEB_READ, MCP }

/** Revisions change on endpoint, credential, policy, or tool-definition changes. */
@Serializable
data class AgentToolIdentity(
    val configId: String,
    val configRevision: Long,
    val toolName: String,
    val definitionDigest: String
)

data class AgentToolDescriptor(
    val identity: AgentToolIdentity,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    /** Safe destination for display, never credentials or secret query parameters. */
    val destination: String,
    /** Only application-owned public web adapters may use the two PUBLIC_WEB kinds. */
    val kind: AgentToolKind = AgentToolKind.MCP
) {
    fun modelDefinition() = AgentToolDefinition(AgentFunctionDefinition(name, description, inputSchema))
}

data class AgentToolAvailabilityIssue(val configId: String, val message: String)

data class AgentToolSnapshot(
    val revision: Long,
    val tools: List<AgentToolDescriptor>,
    val issues: List<AgentToolAvailabilityIssue> = emptyList()
)

data class AgentToolInvocation(
    val runId: String,
    val callId: String,
    val tool: AgentToolDescriptor,
    val arguments: JsonObject
)

/** The only argument projection permitted in persisted activity and prompt history. */
data class AgentToolValidation(val displayArguments: JsonObject)

@Serializable
enum class SourceKind { SEARCH_HIT, FETCHED_PAGE, TOOL_RESOURCE }

@Serializable
data class SourceReference(
    val url: String,
    val title: String,
    val kind: SourceKind,
    /** Assigned by the runtime from actual results, not by the model. */
    val id: String = "",
    val toolCallId: String = "",
    val excerpt: String? = null
)

@Serializable
data class AgentToolResult(
    val content: String,
    val isError: Boolean = false,
    val sources: List<SourceReference> = emptyList(),
    val truncated: Boolean = false,
    /** A request may have acted remotely even when no response was received. */
    val outcomeUnknown: Boolean = false
)

open class AgentToolException(val userMessage: String, cause: Exception? = null) :
    IOException(userMessage, cause)

class AgentToolConfigurationChangedException :
    AgentToolException("Tool configuration changed; the previous proposal is no longer valid")

/**
 * Separate, credential-isolated tool boundary. No Copilot account or token is passed here.
 *
 * snapshot() returns detached definitions. validate() uses a maintained schema validator,
 * rejects unsupported schemas, and has no external side effects. It must reject stale
 * identity/configuration and return a credential-free display projection.
 *
 * execute() must recheck the exact identity and validate arguments before connecting,
 * bind the connection to that configuration snapshot, and cancel on revision changes.
 * Never resolve a changed endpoint by display name, silently retry unknown actions,
 * trust an MCP readOnlyHint, or include credentials in results/errors/source metadata.
 * Implementations bound network bodies before allocation; runtime caps are a second guard.
 */
interface AgentToolExecutor {
    val revision: StateFlow<Long>
    suspend fun snapshot(): AgentToolSnapshot
    suspend fun validate(invocation: AgentToolInvocation): AgentToolValidation
    fun isCurrent(identity: AgentToolIdentity): Boolean
    suspend fun execute(invocation: AgentToolInvocation): AgentToolResult
}
