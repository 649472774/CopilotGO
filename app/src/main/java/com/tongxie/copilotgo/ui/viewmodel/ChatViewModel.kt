package com.tongxie.copilotgo.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.tongxie.copilotgo.data.chat.AttachmentRef
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.SendResult

class ChatViewModel(
    private val sessionId: String,
    private val center: ChatStreamCenter
) : ViewModel() {
    init { center.retain(sessionId) }

    val session = center.sessionFlow(sessionId)
    val loadState = center.loadState(sessionId)
    val sending = center.sendingFlow(sessionId)
    val error = center.errorFlow(sessionId)
    val notice = center.noticeFlow(sessionId)

    fun reload() = center.reload(sessionId)
    fun setModel(model: String) = center.setModel(sessionId, model)
    suspend fun setModelAndAwait(model: String) = center.setModelAndAwait(sessionId, model)

    suspend fun submit(
        text: String,
        attachments: List<String> = emptyList(),
        imageUrls: List<String> = emptyList(),
        attachmentRefs: List<AttachmentRef> = emptyList(),
        submissionId: String? = null
    ): SendResult = center.submit(sessionId, text, attachments, imageUrls, attachmentRefs, submissionId)

    fun send(text: String, attachments: List<String> = emptyList(), imageUrls: List<String> = emptyList()) =
        center.send(sessionId, text, attachments, imageUrls)

    suspend fun importAttachment(resolver: ContentResolver, uri: Uri) = center.importAttachment(resolver, uri)
    fun attachmentFile(ref: AttachmentRef) = center.attachmentFile(ref)
    fun stopStreaming() = center.stop(sessionId)
    fun clearError() = center.clearError(sessionId)
    fun retryLast() = center.retryLast(sessionId)
    suspend fun retryLastAndAwait() = center.retryLastAndAwait(sessionId)
    fun regenerate(assistantMsgId: String) = center.regenerate(sessionId, assistantMsgId)
    suspend fun regenerateAndAwait(assistantMsgId: String) = center.regenerateAndAwait(sessionId, assistantMsgId)
    fun deleteMessage(msgId: String) = center.deleteMessage(sessionId, msgId)
    suspend fun deleteMessageAndAwait(msgId: String) = center.deleteMessageAndAwait(sessionId, msgId)
    fun editAndResend(msgId: String, newText: String) = center.editAndResend(sessionId, msgId, newText)
    suspend fun editAndResendAndAwait(msgId: String, newText: String) =
        center.editAndResendAndAwait(sessionId, msgId, newText)

    override fun onCleared() {
        center.release(sessionId)
        super.onCleared()
    }
}
