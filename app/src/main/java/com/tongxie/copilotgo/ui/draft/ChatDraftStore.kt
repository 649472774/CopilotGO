package com.tongxie.copilotgo.ui.draft

import com.tongxie.copilotgo.data.chat.AttachmentRef
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ComposerDraft(
    val text: String = "",
    val attachments: List<AttachmentRef> = emptyList(),
    val revision: Long = 0,
    val submissionId: String = UUID.randomUUID().toString()
) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()

    fun clearedIfAccepted(submitted: ComposerDraft): ComposerDraft =
        if (this == submitted) ComposerDraft(revision = revision + 1) else this
}

object DraftLimits {
    const val TEXT_CHARS = 32_768
    const val ATTACHMENTS = 8
    const val TOTAL_BYTES = 16L * 1_024 * 1_024
    const val MANIFEST_BYTES = 512 * 1_024
}

class ChatDraftStore(private val directory: File) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(sessionId: String): ComposerDraft = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = file(sessionId)
            if (!file.exists()) return@withContext ComposerDraft()
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, DraftLimits.MANIFEST_BYTES - total + 1))
                    if (count == -1) break
                    total += count
                    if (total > DraftLimits.MANIFEST_BYTES) throw IOException("Draft manifest exceeds limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            json.decodeFromString<ComposerDraft>(text).also(::validate)
        }
    }

    suspend fun save(sessionId: String, draft: ComposerDraft) = mutex.withLock {
        withContext(Dispatchers.IO) {
            validate(draft)
            val destination = file(sessionId)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create draft directory")
            val bytes = json.encodeToString(ComposerDraft.serializer(), draft).toByteArray(Charsets.UTF_8)
            if (bytes.size > DraftLimits.MANIFEST_BYTES) throw IOException("Draft manifest exceeds limit")
            val temporary = File(directory, "${destination.name}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                if (temporary.exists() && !temporary.delete()) {
                    com.tongxie.copilotgo.util.Logger.w("Could not remove unfinished draft")
                }
            }
        }
    }

    suspend fun delete(sessionId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = file(sessionId)
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(File(directory, "${file.name}.tmp").toPath())
            Unit
        }
    }

    private fun file(sessionId: String): File {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid draft session ID" }
        return File(directory, "$sessionId.json")
    }

    private fun validate(draft: ComposerDraft) {
        require(draft.submissionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid submission ID" }
        require(draft.text.length <= DraftLimits.TEXT_CHARS) { "Draft text exceeds limit" }
        require(draft.attachments.size <= DraftLimits.ATTACHMENTS) { "Too many draft attachments" }
        require(draft.attachments.all { it.sizeBytes in 0..DraftLimits.TOTAL_BYTES }) { "Invalid attachment size" }
        require(draft.attachments.sumOf { it.sizeBytes } <= DraftLimits.TOTAL_BYTES) { "Draft attachments exceed limit" }
    }
}
