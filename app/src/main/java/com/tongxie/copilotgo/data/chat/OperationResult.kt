package com.tongxie.copilotgo.data.chat

sealed interface OperationResult {
    data object Accepted : OperationResult
    data class Rejected(val message: String) : OperationResult
}

sealed interface SendResult {
    data class Accepted(val userMessageId: String) : SendResult
    data class Rejected(val message: String) : SendResult
}

sealed interface SessionLoadState {
    data object Loading : SessionLoadState
    data object Ready : SessionLoadState
    data object Missing : SessionLoadState
    data class Failed(val message: String) : SessionLoadState
}
