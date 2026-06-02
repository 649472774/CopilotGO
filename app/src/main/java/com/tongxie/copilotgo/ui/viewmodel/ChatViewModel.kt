package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.Session
import kotlinx.coroutines.flow.StateFlow

/**
 * 瘦壳 wrapper：所有真实状态/任务由 [ChatStreamCenter] 持有（singleton，
 * Application 生命周期）。本 VM 销毁不会 cancel 正在进行的 SSE。
 *
 * 详见 ChatStreamCenter 注释 & AGENTS.md §27。
 */
class ChatViewModel(
    private val sessionId: String,
    private val center: ChatStreamCenter
) : ViewModel() {

    val session: StateFlow<Session?> = center.sessionFlow(sessionId)
    val sending: StateFlow<Boolean> = center.sendingFlow(sessionId)
    val error: StateFlow<String?> = center.errorFlow(sessionId)

    fun setModel(model: String) = center.setModel(sessionId, model)

    fun send(
        text: String,
        attachments: List<String> = emptyList(),
        imageUrls: List<String> = emptyList()
    ) = center.send(sessionId, text, attachments, imageUrls)

    fun stopStreaming() = center.stop(sessionId)

    fun clearError() = center.clearError(sessionId)

    fun retryLast() = center.retryLast(sessionId)

    fun regenerate(assistantMsgId: String) = center.regenerate(sessionId, assistantMsgId)

    fun deleteMessage(msgId: String) = center.deleteMessage(sessionId, msgId)

    fun editAndResend(msgId: String, newText: String) =
        center.editAndResend(sessionId, msgId, newText)
}
