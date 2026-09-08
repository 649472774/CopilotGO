package com.tongxie.copilotgo.data.tools

import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable
enum class ToolProblemCode {
    INVALID_CONFIGURATION,
    CONFIGURATION_CHANGED,
    STORAGE,
    DISABLED,
    CONSENT_REQUIRED,
    AUTHENTICATION_REQUIRED,
    RATE_LIMITED,
    UNSAFE_DESTINATION,
    UNSAFE_PROXY_ROUTE,
    NETWORK,
    TOO_LARGE,
    UNSUPPORTED_TRANSPORT,
    UNSUPPORTED_CONTENT,
    UNSUPPORTED_INTERACTION,
    PROTOCOL,
    SCHEMA,
    TOOL_REPORTED_ERROR,
    UNKNOWN_OUTCOME
}

@Serializable
data class ToolProblem(
    val code: ToolProblemCode,
    val message: String,
    val retryable: Boolean = false,
    val httpStatus: Int? = null
)

/** Carries only an application-authored diagnostic, never a remote body or credential. */
class ToolException(val problem: ToolProblem) : IOException(problem.message)

internal fun toolFailure(code: ToolProblemCode, message: String): Nothing =
    throw ToolException(ToolProblem(code, message))
