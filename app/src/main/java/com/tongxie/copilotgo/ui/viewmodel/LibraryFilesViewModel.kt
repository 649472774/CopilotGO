package com.tongxie.copilotgo.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.storage.SessionStore
import com.tongxie.copilotgo.data.storage.SessionStorageException
import com.tongxie.copilotgo.ui.files.ExportEntry
import com.tongxie.copilotgo.ui.files.ExportFileStore
import com.tongxie.copilotgo.ui.files.ExportPreview
import com.tongxie.copilotgo.ui.files.ExportTooLargeException
import com.tongxie.copilotgo.util.Logger
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LibraryResult<out T> {
    data class Success<T>(val value: T, val warning: String? = null) : LibraryResult<T>
    data class Failure(val message: String) : LibraryResult<Nothing>
}

data class ExportListState(
    val loading: Boolean = true,
    val entries: List<ExportEntry> = emptyList(),
    val error: String? = null
)

class LibraryFilesViewModel(
    context: Context,
    private val store: SessionStore,
    private val discardDraft: suspend (String) -> Unit,
    private val readExportEntries: suspend (ExportFileStore) -> List<ExportEntry> = { it.list() }
) : ViewModel() {
    private val appContext = context.applicationContext
    private val exports = viewModelScope.async(Dispatchers.IO) { ExportFileStore(appContext.cacheDir) }
    val summaries = store.summaries
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _exportsState = MutableStateFlow(ExportListState())
    val exportsState: StateFlow<ExportListState> = _exportsState
    private var reloadJob: Job? = null

    init {
        reloadExports()
    }

    fun reloadExports() {
        reloadJob?.cancel()
        _exportsState.value = _exportsState.value.copy(loading = true, error = null)
        reloadJob = viewModelScope.launch {
            val result = read { readExportEntries(exports.await()) }
            currentCoroutineContext().ensureActive()
            when (result) {
                is LibraryResult.Success -> _exportsState.value = ExportListState(loading = false, entries = result.value)
                is LibraryResult.Failure -> _exportsState.value = _exportsState.value.copy(loading = false, error = result.message)
            }
        }
    }

    suspend fun createSession(creator: suspend () -> Session): LibraryResult<Session> = operation(creator)

    suspend fun deleteSession(id: String): LibraryResult<Unit> {
        val result = operation {
            // A committed core deletion must not strand its draft during navigation.
            withContext(NonCancellable) {
                store.delete(id)
                try {
                    discardDraft(id)
                    null
                } catch (_: IOException) {
                    Logger.w("Session deleted but draft cleanup failed")
                    appContext.getString(R.string.draft_cleanup_failed)
                } catch (_: SecurityException) {
                    Logger.w("Session deleted but draft cleanup was denied")
                    appContext.getString(R.string.draft_cleanup_failed)
                }
            }
        }
        return when (result) {
            is LibraryResult.Success -> LibraryResult.Success(Unit, warning = result.value)
            is LibraryResult.Failure -> result
        }
    }

    suspend fun renameSession(id: String, title: String): LibraryResult<Unit> = operation {
        require(title.isNotBlank() && title.length <= 120)
        store.rename(id, title.trim())
    }

    suspend fun setPinned(id: String, pinned: Boolean): LibraryResult<Unit> = operation { store.setPinned(id, pinned) }

    suspend fun exportSession(id: String): LibraryResult<File> = operation {
        val session = store.getSession(id) ?: throw IOException("Session is no longer available")
        val store = exports.await()
        val entry = store.writeSession(
            session,
            attachmentNames = session.messages.associate { message ->
                message.id to message.attachments.map { it.name }
            }
        )
        reloadExports()
        store.file(entry.name)
    }

    suspend fun exportMessage(message: UiMessage): LibraryResult<File> = operation {
        val store = exports.await()
        val entry = store.writeMessage(message, message.attachments.map { it.name })
        reloadExports()
        store.file(entry.name)
    }

    suspend fun shareExport(name: String): LibraryResult<File> = operation { exports.await().file(name) }

    suspend fun deleteExport(name: String): LibraryResult<Unit> = operation {
        exports.await().delete(name)
        reloadExports()
    }

    suspend fun previewExport(name: String): LibraryResult<ExportPreview> = read { exports.await().preview(name) }

    private suspend fun <T> operation(block: suspend () -> T): LibraryResult<T> =
        withContext(Dispatchers.Main.immediate) {
            if (_busy.value) return@withContext LibraryResult.Failure(appContext.getString(R.string.library_busy))
            _busy.value = true
            try {
                read(block)
            } finally {
                _busy.value = false
            }
        }

    private suspend fun <T> read(block: suspend () -> T): LibraryResult<T> = try {
        LibraryResult.Success(block())
    } catch (_: ExportTooLargeException) {
        LibraryResult.Failure(appContext.getString(R.string.export_too_large))
    } catch (error: SessionStorageException) {
        Logger.w("Session storage operation could not complete")
        LibraryResult.Failure(error.userMessage)
    } catch (_: IOException) {
        Logger.w("Library operation could not complete")
        LibraryResult.Failure(appContext.getString(R.string.library_io_error))
    } catch (_: SecurityException) {
        Logger.w("Library operation access denied")
        LibraryResult.Failure(appContext.getString(R.string.library_access_error))
    } catch (_: IllegalArgumentException) {
        Logger.w("Library operation rejected invalid input")
        LibraryResult.Failure(appContext.getString(R.string.library_invalid))
    }
}
