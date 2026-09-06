package com.tongxie.copilotgo.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.chat.AttachmentRef
import com.tongxie.copilotgo.data.chat.SendResult
import com.tongxie.copilotgo.data.storage.AttachmentImportException
import com.tongxie.copilotgo.ui.draft.ChatDraftStore
import com.tongxie.copilotgo.ui.draft.ComposerDraft
import com.tongxie.copilotgo.ui.draft.DraftLimits
import com.tongxie.copilotgo.util.Logger
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

enum class DraftProblem { NOT_READY, BUSY, EMPTY, TEXT_LIMIT, COUNT_LIMIT, TOTAL_LIMIT, IMPORT_FAILED }
data class DraftNotice(val serial: Long, val problem: DraftProblem? = null, val rejection: String? = null)

data class DraftUiState(
    val draft: ComposerDraft = ComposerDraft(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val importing: Boolean = false,
    val submitting: Boolean = false,
    val acceptedSerial: Long = 0,
    val notice: DraftNotice? = null
)

/**
 * Activity-scoped draft owner. Navigating away never cancels an accepted import or
 * the ordered, conflated-per-session disk writes. Only references enter manifests.
 */
class ChatDraftsViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val store = viewModelScope.async(Dispatchers.IO) {
        ChatDraftStore(File(appContext.noBackupFilesDir, "ui-drafts"))
    }
    private class Entry {
        val state = MutableStateFlow(DraftUiState())
        var loaded = CompletableDeferred<Unit>()
        var users = 0
        var dirty = false
        var removed = false
        val jobs = mutableListOf<Job>()
    }
    private val entries = LinkedHashMap<String, Entry>()
    private val pending = LinkedHashMap<String, ComposerDraft>()
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)
    private var noticeSerial = 0L

    init {
        viewModelScope.launch {
            try {
                for (signal in writeSignal) flushPending()
            } finally {
                withContext(NonCancellable) {
                    flushPending()
                }
            }
        }
    }

    fun state(sessionId: String): StateFlow<DraftUiState> = entry(sessionId).state

    fun retain(sessionId: String) {
        entry(sessionId).users++
    }

    fun release(sessionId: String) {
        entries[sessionId]?.let { it.users = (it.users - 1).coerceAtLeast(0) }
        trimInactive()
    }

    fun reload(sessionId: String) {
        val entry = entry(sessionId)
        if (entry.state.value.loading) return
        entry.loaded = CompletableDeferred()
        load(sessionId, entry)
    }

    fun updateText(sessionId: String, text: String) {
        val entry = entry(sessionId)
        if (!editable(entry)) return
        if (text.length > DraftLimits.TEXT_CHARS) {
            notify(entry, DraftProblem.TEXT_LIMIT)
            return
        }
        replace(sessionId, entry, entry.state.value.draft.copy(text = text))
    }

    fun appendSpeech(sessionId: String, text: String) {
        val entry = entry(sessionId)
        launchFor(entry) {
            entry.loaded.await()
            val current = entry.state.value.draft.text
            updateText(sessionId, if (current.isBlank()) text else "$current $text")
        }
    }

    fun removeAttachment(sessionId: String, id: String) {
        val entry = entry(sessionId)
        if (!editable(entry)) return
        replace(
            sessionId,
            entry,
            entry.state.value.draft.copy(attachments = entry.state.value.draft.attachments.filterNot { it.id == id })
        )
    }

    fun importAttachments(
        sessionId: String,
        uris: List<Uri>,
        importer: suspend (Uri) -> AttachmentRef
    ) {
        if (uris.isEmpty()) return
        val entry = entry(sessionId)
        if (entry.removed || entry.state.value.importing || entry.state.value.submitting) {
            notify(entry, DraftProblem.BUSY)
            return
        }
        entry.state.value = entry.state.value.copy(importing = true)
        launchFor(entry) {
            try {
                entry.loaded.await()
                if (entry.state.value.loadFailed) {
                    notify(entry, DraftProblem.NOT_READY)
                    return@launchFor
                }
                for (uri in uris) {
                    val current = entry.state.value.draft
                    if (current.attachments.size >= DraftLimits.ATTACHMENTS) {
                        notify(entry, DraftProblem.COUNT_LIMIT)
                        break
                    }
                    try {
                        val attachment = importer(uri)
                        val latest = entry.state.value.draft
                        if (latest.attachments.any { it.id == attachment.id }) continue
                        if (attachment.sizeBytes > DraftLimits.TOTAL_BYTES - latest.attachments.sumOf { it.sizeBytes }) {
                            notify(entry, DraftProblem.TOTAL_LIMIT)
                            continue
                        }
                        replace(sessionId, entry, latest.copy(attachments = latest.attachments + attachment))
                    } catch (error: AttachmentImportException) {
                        entry.state.value = entry.state.value.copy(
                            notice = DraftNotice(++noticeSerial, rejection = error.userMessage)
                        )
                    } catch (_: IOException) {
                        Logger.w("Draft attachment could not be imported")
                        notify(entry, DraftProblem.IMPORT_FAILED)
                    } catch (_: IllegalArgumentException) {
                        Logger.w("Draft attachment was rejected")
                        notify(entry, DraftProblem.IMPORT_FAILED)
                    } catch (_: SecurityException) {
                        Logger.w("Draft attachment access denied")
                        notify(entry, DraftProblem.IMPORT_FAILED)
                    }
                }
            } finally {
                entry.state.value = entry.state.value.copy(importing = false)
                trimInactive()
            }
        }
    }

    fun submit(sessionId: String, sender: suspend (ComposerDraft) -> SendResult) {
        val entry = entry(sessionId)
        if (!editable(entry)) return
        if (entry.state.value.importing) {
            notify(entry, DraftProblem.BUSY)
            return
        }
        val submitted = entry.state.value.draft
        if (submitted.isEmpty) {
            notify(entry, DraftProblem.EMPTY)
            return
        }
        entry.state.value = entry.state.value.copy(submitting = true)
        launchFor(entry) {
            try {
                if (!persistBeforeSubmit(sessionId, entry, submitted)) return@launchFor
                when (val result = sender(submitted)) {
                    is SendResult.Accepted -> {
                        val current = entry.state.value.draft
                        val next = current.clearedIfAccepted(submitted)
                        if (next != current) replace(sessionId, entry, next)
                        entry.state.value = entry.state.value.copy(acceptedSerial = entry.state.value.acceptedSerial + 1)
                    }
                    is SendResult.Rejected -> {
                        entry.state.value = entry.state.value.copy(
                            notice = DraftNotice(++noticeSerial, rejection = result.message)
                        )
                    }
                }
            } finally {
                entry.state.value = entry.state.value.copy(submitting = false)
                trimInactive()
            }
        }
    }

    fun retrySave(sessionId: String) {
        val entry = entry(sessionId)
        if (!entry.state.value.loadFailed) queueWrite(sessionId, entry)
    }

    fun clearNotice(sessionId: String, serial: Long) {
        val entry = entry(sessionId)
        if (entry.state.value.notice?.serial == serial) {
            entry.state.value = entry.state.value.copy(notice = null)
        }
    }

    suspend fun discard(sessionId: String) {
        val entry = entries[sessionId]
        if (entry != null) {
            entry.removed = true
            entry.jobs.forEach { it.cancel() }
            entry.jobs.toList().forEach { it.join() }
        }
        pending.remove(sessionId)
        store.await().delete(sessionId)
        entries.remove(sessionId)
    }

    private fun entry(sessionId: String): Entry {
        entries[sessionId]?.let { return it }
        val entry = Entry()
        entries[sessionId] = entry
        load(sessionId, entry)
        return entry
    }

    private fun load(sessionId: String, entry: Entry) {
        entry.state.value = entry.state.value.copy(loading = true, loadFailed = false)
        launchFor(entry) {
            try {
                val draft = store.await().load(sessionId)
                entry.state.value = entry.state.value.copy(draft = draft, loading = false)
            } catch (_: IOException) {
                failedLoad(entry)
            } catch (_: SerializationException) {
                failedLoad(entry)
            } catch (_: IllegalArgumentException) {
                failedLoad(entry)
            } catch (_: SecurityException) {
                failedLoad(entry)
            } finally {
                entry.loaded.complete(Unit)
            }
        }
    }

    private fun failedLoad(entry: Entry) {
        Logger.w("Draft could not be restored; existing file retained")
        entry.state.value = entry.state.value.copy(loading = false, loadFailed = true)
    }

    private fun editable(entry: Entry): Boolean {
        val current = entry.state.value
        return when {
            entry.removed || current.loading || current.loadFailed -> { notify(entry, DraftProblem.NOT_READY); false }
            current.submitting -> { notify(entry, DraftProblem.BUSY); false }
            else -> true
        }
    }

    private fun notify(entry: Entry, problem: DraftProblem) {
        entry.state.value = entry.state.value.copy(notice = DraftNotice(++noticeSerial, problem))
    }

    private fun replace(sessionId: String, entry: Entry, draft: ComposerDraft) {
        val current = entry.state.value.draft
        if (current.text == draft.text && current.attachments == draft.attachments) return
        entry.state.value = entry.state.value.copy(draft = draft.copy(
            revision = current.revision + 1,
            submissionId = UUID.randomUUID().toString()
        ))
        queueWrite(sessionId, entry)
    }

    private fun queueWrite(sessionId: String, entry: Entry) {
        if (entry.removed) {
            notify(entry, DraftProblem.NOT_READY)
            return
        }
        entry.dirty = true
        entry.state.value = entry.state.value.copy(saving = true)
        pending[sessionId] = entry.state.value.draft
        writeSignal.trySend(Unit)
    }

    private suspend fun flushPending() {
        while (pending.isNotEmpty()) {
            val (id, draft) = pending.entries.first().let { it.key to it.value }
            val entry = entries[id]
            if (entry == null) {
                pending.remove(id)
                continue
            }
            try {
                store.await().save(id, draft)
                if (pending[id] == draft) pending.remove(id)
                if (entry.state.value.draft.revision == draft.revision) {
                    entry.dirty = false
                    entry.state.value = entry.state.value.copy(saving = false, saveFailed = false)
                }
            } catch (_: IOException) {
                if (pending[id] == draft) pending.remove(id)
                failedSave(entry)
            } catch (_: SerializationException) {
                if (pending[id] == draft) pending.remove(id)
                failedSave(entry)
            } catch (_: IllegalArgumentException) {
                if (pending[id] == draft) pending.remove(id)
                failedSave(entry)
            } catch (_: SecurityException) {
                if (pending[id] == draft) pending.remove(id)
                failedSave(entry)
            }
        }
        trimInactive()
    }

    private suspend fun persistBeforeSubmit(id: String, entry: Entry, draft: ComposerDraft): Boolean = try {
        store.await().save(id, draft)
        if (pending[id] == draft) pending.remove(id)
        entry.dirty = false
        entry.state.value = entry.state.value.copy(saving = false, saveFailed = false)
        true
    } catch (_: IOException) {
        failedSave(entry)
        false
    } catch (_: SerializationException) {
        failedSave(entry)
        false
    } catch (_: IllegalArgumentException) {
        failedSave(entry)
        false
    } catch (_: SecurityException) {
        failedSave(entry)
        false
    }

    private fun failedSave(entry: Entry) {
        Logger.w("Draft could not be saved; in-memory draft retained")
        entry.state.value = entry.state.value.copy(saving = false, saveFailed = true)
    }

    private fun launchFor(entry: Entry, block: suspend () -> Unit) {
        entry.jobs.removeAll { it.isCompleted }
        entry.jobs += viewModelScope.launch { block() }
    }

    private fun trimInactive() {
        if (entries.size <= 8) return
        val iterator = entries.iterator()
        while (entries.size > 8 && iterator.hasNext()) {
            val (_, entry) = iterator.next()
            val state = entry.state.value
            if (entry.users == 0 && !entry.dirty && !state.loading && !state.importing && !state.submitting) {
                iterator.remove()
            }
        }
    }
}
