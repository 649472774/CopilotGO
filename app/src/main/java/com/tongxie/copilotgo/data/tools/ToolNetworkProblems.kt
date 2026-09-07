package com.tongxie.copilotgo.data.tools

import com.tongxie.copilotgo.data.tools.net.ToolNetworkErrorCode
import com.tongxie.copilotgo.data.tools.net.ToolNetworkException

internal fun ToolNetworkException.toToolProblem(): ToolProblem {
    val problemCode = when (code) {
        ToolNetworkErrorCode.UNSAFE_URL, ToolNetworkErrorCode.UNSAFE_DNS, ToolNetworkErrorCode.UNSAFE_REDIRECT ->
            ToolProblemCode.UNSAFE_DESTINATION
        ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE -> ToolProblemCode.UNSAFE_PROXY_ROUTE
        ToolNetworkErrorCode.UNSUPPORTED_CONTENT_TYPE, ToolNetworkErrorCode.UNSUPPORTED_CONTENT_ENCODING ->
            ToolProblemCode.UNSUPPORTED_CONTENT
        ToolNetworkErrorCode.INVALID_RESPONSE -> ToolProblemCode.PROTOCOL
        ToolNetworkErrorCode.RESPONSE_TOO_LARGE -> ToolProblemCode.TOO_LARGE
        ToolNetworkErrorCode.NETWORK_ERROR -> ToolProblemCode.NETWORK
    }
    return ToolProblem(problemCode, checkNotNull(message), retryable = code == ToolNetworkErrorCode.NETWORK_ERROR)
}

internal val ToolNetworkException.couldHaveExecuted: Boolean get() = code in setOf(
    ToolNetworkErrorCode.UNSUPPORTED_CONTENT_TYPE,
    ToolNetworkErrorCode.UNSUPPORTED_CONTENT_ENCODING,
    ToolNetworkErrorCode.INVALID_RESPONSE,
    ToolNetworkErrorCode.RESPONSE_TOO_LARGE,
    ToolNetworkErrorCode.NETWORK_ERROR
)
