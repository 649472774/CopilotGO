package com.tongxie.copilotgo.data.storage

import com.tongxie.copilotgo.data.chat.AttachmentKind
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.SessionLoadState
import com.tongxie.copilotgo.data.chat.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

open class SessionStorageException(val userMessage: String, cause: Exception? = null) :
    IOException(userMessage, cause)

class SessionDeletedException : SessionStorageException("会话已删除或正在删除")
class SessionConflictException : SessionStorageException("会话已更新，请重新读取后再操作")

data class StorageIssue(val sessionId: String?, val message: String, val recovered: Boolean = false)

/** The only authority for live conversations, metadata, snapshots, and disk mutations. */
class SessionStore(
    private val paths: AppPaths,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxInactiveSessions: Int = 8,
    private val maxInactiveBytes: Long = 16L * 1024 * 1024
) {
    val attachments = AttachmentStore(paths)
    private val mutex = Mutex()
    private val cacheGuard = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    private val dirtyMigrations = ConcurrentHashMap.newKeySet<String>()
    private val deletionListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private var loaded = false
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _issues = MutableStateFlow<List<StorageIssue>>(emptyList())
    val issues = _issues.asStateFlow()
    private val _summaries = MutableStateFlow<List<SessionSummary>>(emptyList())
    val summaries = _summaries.asStateFlow()
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    /** Metadata-only compatibility projection. Use getSession/sessionFlow for message history. */
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val sorter = compareByDescending<SessionSummary> { it.pinned }
        .thenByDescending { it.updatedAt }.thenBy { it.id }

    private class Entry {
        var current: Session? = null
        val flow = MutableStateFlow<Session?>(null)
        val loadState = MutableStateFlow<SessionLoadState>(SessionLoadState.Loading)
        var owners = 0
        var loading = false
        var dirty = false
    }

    private fun entry(id: String): Entry = synchronized(cacheGuard) {
        validateId(id)
        entries.getOrPut(id) { Entry() }
    }

    fun retain(id: String) {
        synchronized(cacheGuard) { entry(id).owners++ }
    }

    fun release(id: String) {
        synchronized(cacheGuard) {
            entries[id]?.let { it.owners = (it.owners - 1).coerceAtLeast(0) }
            trimCacheLocked()
        }
    }

    fun sessionFlow(id: String): StateFlow<Session?> {
        val entry = entry(id)
        synchronized(cacheGuard) {
            if (entry.current == null && !entry.loading && entry.loadState.value != SessionLoadState.Missing) {
                entry.loading = true
                scope.launch {
                    try {
                        getSession(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        entry.loadState.value = SessionLoadState.Failed(storageMessage(e))
                    } finally {
                        synchronized(cacheGuard) { entry.loading = false }
                    }
                }
            }
        }
        return entry.flow.asStateFlow()
    }

    fun loadState(id: String): StateFlow<SessionLoadState> = entry(id).loadState.asStateFlow()

    suspend fun load(force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(force) }
    }

    private suspend fun loadLocked(force: Boolean = false) {
        if (loaded && !force) return
        _loading.value = true
        try {
            val directory = AtomicFiles.ensureDirectory(paths.sessions)
            val files = directory.listFiles() ?: throw SessionStorageException("无法读取会话目录")
            val ids = files.mapNotNull { file ->
                when {
                    file.name.endsWith(".json.bak") -> file.name.removeSuffix(".json.bak")
                    file.name.endsWith(".json") -> file.name.removeSuffix(".json")
                    else -> null
                }
            }.distinct()
            val result = mutableListOf<SessionSummary>()
            for (id in ids) {
                currentCoroutineContext().ensureActive()
                if (!validId(id)) {
                    report(StorageIssue(null, "发现不受支持的会话文件名，原文件已保留"))
                    continue
                }
                if (id in deleted || tombstone(id).exists()) {
                    continue
                }
                val live = synchronized(cacheGuard) { entries[id]?.current }
                if (live != null && primary(id).exists()) {
                    result.add(summary(live))
                    continue
                }
                try {
                    val cached = readSummary(id)
                    result.add(cached ?: summary(readSession(id)).also { writeSummary(it) })
                } catch (e: IOException) {
                    val message = storageMessage(e)
                    report(StorageIssue(id, message))
                    val old = _summaries.value.firstOrNull { it.id == id }
                    result.add(old?.copy(loadError = message) ?: SessionSummary(
                        id, "无法读取的会话", "", 0, primary(id).lastModified(), false,
                        0, 0, "", false, message
                    ))
                }
            }
            publishSummaries(result)
            loaded = true
        } catch (e: IOException) {
            report(StorageIssue(null, storageMessage(e)))
            throw e
        } finally {
            _loading.value = false
        }
    }

    suspend fun getSession(id: String, reload: Boolean = false): Session? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = entry(id)
            try {
                loadLocked()
                if (id in deleted || tombstone(id).exists() || _summaries.value.none { it.id == id }) {
                    entry.loadState.value = SessionLoadState.Missing
                    entry.flow.value = null
                    return@withLock null
                }
                if (!primary(id).exists()) {
                    recordExternalDeletion(id)
                    entry.loadState.value = SessionLoadState.Missing
                    entry.flow.value = null
                    return@withLock null
                }
                val existing = synchronized(cacheGuard) { entry.current }
                if (existing != null && (!reload || entry.dirty || entry.owners > 0)) {
                    entry.loadState.value = SessionLoadState.Ready
                    return@withLock snapshot(existing)
                }
                entry.loadState.value = SessionLoadState.Loading
                val session = readSession(id)
                publish(entry, session, dirty = id in dirtyMigrations)
                writeSummary(summary(session))
                snapshot(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = storageMessage(e)
                entry.loadState.value = SessionLoadState.Failed(message)
                report(StorageIssue(id, message))
                throw SessionStorageException(message, e)
            } finally {
                synchronized(cacheGuard) { trimCacheLocked() }
            }
        }
    }

    suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            validateId(session.id)
            ensureWritable(session.id, _summaries.value.any { it.id == session.id })
            val existing = currentLocked(session.id)
            ensureWritable(session.id, existing != null)
            if (existing != null && session.revision != existing.revision) throw SessionConflictException()
            val next = snapshot(session).copy(
                revision = (existing?.revision ?: session.revision) + 1,
                updatedAt = System.currentTimeMillis()
            )
            persistLocked(next)
            publish(entry(next.id), next, dirty = false)
            synchronized(cacheGuard) { trimCacheLocked() }
        }
    }

    suspend fun update(
        id: String,
        persist: Boolean = true,
        transform: (Session) -> Session
    ): Session = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            ensureWritable(id, true)
            val current = currentLocked(id) ?: throw SessionDeletedException()
            val proposed = transform(snapshot(current))
            require(proposed.id == id) { "会话标识不可更改" }
            val next = snapshot(proposed).copy(
                revision = current.revision + 1, updatedAt = System.currentTimeMillis()
            )
            if (persist) persistLocked(next)
            publish(entry(id), next, dirty = !persist, updateSummary = persist)
            snapshot(next)
        }
    }

    suspend fun persist(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureWritable(id, true)
            val current = currentLocked(id) ?: throw SessionDeletedException()
            persistLocked(current)
            synchronized(cacheGuard) { entries[id]?.dirty = false }
            dirtyMigrations.remove(id)
            publishSummaries(_summaries.value.filterNot { it.id == id } + summary(current))
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        update(id) { it.copy(pinned = pinned) }
    }

    suspend fun rename(id: String, newTitle: String) {
        val title = newTitle.trim()
        require(title.isNotEmpty() && title.length <= 120) { "标题需为 1 至 120 个字符" }
        update(id) { it.copy(title = title) }
    }

    internal fun addDeletionListener(listener: (String) -> Unit) { deletionListeners.add(listener) }
    internal fun removeDeletionListener(listener: (String) -> Unit) { deletionListeners.remove(listener) }

    fun markDeleting(id: String) {
        validateId(id)
        deleted.add(id)
        deletionListeners.forEach { it(id) }
    }

    suspend fun delete(sessionId: String) {
        markDeleting(sessionId)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    deleteLocked(sessionId)
                } catch (e: IOException) {
                    report(StorageIssue(sessionId, "删除未完成，已阻止后续写入，请重试"))
                    throw SessionStorageException("删除未完成，请重试", e)
                }
            }
        }
    }

    private fun deleteLocked(id: String) {
        AtomicFiles.write(tombstone(id), byteArrayOf(1))
        val directory = paths.sessions
        val exactNames = setOf("$id.json", "$id.json.tmp", "$id.json.bak", "$id.json.bak.tmp", "$id.summary", "$id.summary.tmp")
        directory.listFiles()?.filter {
            it.name in exactNames || it.name.startsWith("$id.json.corrupt-")
        }?.forEach { Files.deleteIfExists(it.toPath()) }
        synchronized(cacheGuard) {
            entries[id]?.let {
                it.current = null
                it.flow.value = null
                it.loadState.value = SessionLoadState.Missing
                it.dirty = false
            }
            trimCacheLocked()
        }
        dirtyMigrations.remove(id)
        publishSummaries(_summaries.value.filterNot { it.id == id })
        deleted.remove(id)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked(force = true)
            val ids = _summaries.value.map { it.id }
            ids.forEach { markDeleting(it) }
            for (id in ids) {
                try {
                    deleteLocked(id)
                } catch (e: IOException) {
                    report(StorageIssue(id, "会话清理未完成，请重试"))
                    throw SessionStorageException("会话清理未完成，请重试", e)
                }
            }
        }
    }

    suspend fun exportSession(id: String): File = withContext(Dispatchers.IO) {
        val snapshot = getSession(id) ?: throw SessionDeletedException()
        val file = File(paths.exports, "$id.json")
        val bytes = json.encodeToString(Session.serializer(), snapshot).toByteArray()
        if (bytes.size > MAX_SESSION_BYTES) throw SessionStorageException("会话超过导出大小限制")
        AtomicFiles.write(file, bytes)
        file
    }

    private suspend fun currentLocked(id: String): Session? {
        validateId(id)
        synchronized(cacheGuard) { entries[id]?.current }?.let { return it }
        if (_summaries.value.none { it.id == id }) return null
        val session = readSession(id)
        publish(entry(id), session, dirty = id in dirtyMigrations)
        return session
    }

    private fun ensureWritable(id: String, mustExist: Boolean) {
        validateId(id)
        if (id in deleted || tombstone(id).exists()) throw SessionDeletedException()
        if (mustExist && !primary(id).exists()) {
            recordExternalDeletion(id)
            throw SessionDeletedException()
        }
    }

    private fun recordExternalDeletion(id: String) {
        markDeleting(id)
        AtomicFiles.write(tombstone(id), byteArrayOf(1))
        publishSummaries(_summaries.value.filterNot { it.id == id })
        deleted.remove(id)
    }

    private fun persistLocked(session: Session) {
        ensureWritable(session.id, _summaries.value.any { it.id == session.id })
        val bytes = json.encodeToString(Session.serializer(), session).toByteArray()
        if (bytes.size > MAX_SESSION_BYTES) throw SessionStorageException("会话超过本地存储大小限制")
        try {
            AtomicFiles.write(primary(session.id), bytes, backup = true)
            writeSummary(summary(session))
        } catch (e: IOException) {
            report(StorageIssue(session.id, "会话保存失败，已保留上次成功保存的内容，请重试"))
            throw SessionStorageException("会话保存失败，请重试", e)
        }
    }

    private suspend fun readSession(id: String): Session {
        validateId(id)
        if (id in deleted || tombstone(id).exists()) throw SessionDeletedException()
        val file = primary(id)
        val original = try {
            decode(file, id)
        } catch (e: IOException) {
            val backup = File(paths.sessions, "$id.json.bak")
            val recovered = try {
                decode(backup, id)
            } catch (_: IOException) {
                throw SessionStorageException("会话文件损坏或无法读取，原文件已保留", e)
            }
            if (file.exists()) {
                Files.move(
                    file.toPath(), File(paths.sessions, "$id.json.corrupt-${UUID.randomUUID()}").toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
            }
            AtomicFiles.write(file, json.encodeToString(Session.serializer(), recovered).toByteArray())
            report(StorageIssue(id, "已从上次备份恢复会话；损坏的原文件已保留", recovered = true))
            recovered
        }
        var changed = false
        val messages = original.messages.map { old ->
            var message = old
            if (old.isStreaming) {
                message = message.copy(
                    content = message.content.ifEmpty { "[已中断]" },
                    isStreaming = false, finishReason = "interrupted"
                )
                changed = true
            }
            if (message.imageUrls.any { it.startsWith("data:") }) {
                try {
                    val imported = message.imageUrls.filter { it.startsWith("data:") }
                        .map { attachments.importDataUri(it, legacy = true) }
                    message = message.copy(
                        imageUrls = message.imageUrls.filterNot { it.startsWith("data:") },
                        attachments = (message.attachments + imported).distinctBy { it.id }
                    )
                    changed = true
                } catch (e: AttachmentImportException) {
                    report(StorageIssue(id, "历史图片未能迁移：${e.userMessage}；原始数据已保留"))
                } catch (_: IOException) {
                    report(StorageIssue(id, "历史图片迁移保存失败，原始数据已保留"))
                }
            }
            message
        }.toMutableList()
        val normalized = original.copy(
            messages = messages, revision = original.revision + if (changed) 1 else 0
        )
        if (changed) {
            try {
                AtomicFiles.write(file, json.encodeToString(Session.serializer(), normalized).toByteArray(), backup = true)
            } catch (_: IOException) {
                dirtyMigrations.add(id)
                report(StorageIssue(id, "历史会话已加载，但迁移保存失败，请重试"))
            }
        }
        return normalized
    }

    private fun decode(file: File, id: String): Session = try {
        if (file.canonicalFile.parentFile != paths.sessions.canonicalFile) {
            throw SessionStorageException("会话文件路径无效")
        }
        val session = json.decodeFromString(
            Session.serializer(), AtomicFiles.read(file, MAX_SESSION_BYTES).toString(Charsets.UTF_8)
        )
        if (session.id != id) throw SessionStorageException("会话标识与文件不匹配")
        snapshot(session)
    } catch (e: SerializationException) {
        throw SessionStorageException("会话文件格式损坏", e)
    }

    private fun readSummary(id: String): SessionSummary? {
        val file = File(paths.sessions, "$id.summary")
        if (!file.isFile || !primary(id).isFile) return null
        val record = try {
            json.decodeFromString(SummaryRecord.serializer(), AtomicFiles.read(file, 16 * 1024).toString(Charsets.UTF_8))
        } catch (_: IOException) {
            report(StorageIssue(id, "会话摘要读取失败，正在从原会话重建"))
            return null
        } catch (_: SerializationException) {
            report(StorageIssue(id, "会话摘要格式损坏，正在从原会话重建"))
            return null
        }
        return record.summary.takeIf {
            it.id == id && record.size == primary(id).length() && record.modifiedAt == primary(id).lastModified()
        }
    }

    private fun writeSummary(summary: SessionSummary) {
        try {
            val file = primary(summary.id)
            val record = SummaryRecord(summary, file.length(), file.lastModified())
            AtomicFiles.write(
                File(paths.sessions, "${summary.id}.summary"),
                json.encodeToString(SummaryRecord.serializer(), record).toByteArray()
            )
        } catch (_: IOException) {
            report(StorageIssue(summary.id, "会话内容已保留，但摘要缓存写入失败"))
        }
    }

    private fun publish(entry: Entry, session: Session, dirty: Boolean, updateSummary: Boolean = true) {
        synchronized(cacheGuard) {
            entry.current = session
            entry.dirty = dirty
            entry.flow.value = snapshot(session)
            entry.loadState.value = SessionLoadState.Ready
        }
        if (updateSummary) {
            publishSummaries(_summaries.value.filterNot { it.id == session.id } + summary(session))
        }
    }

    private fun publishSummaries(values: List<SessionSummary>) {
        val sorted = values.sortedWith(sorter)
        _summaries.value = sorted
        _sessions.value = sorted.map {
            Session(it.id, it.title, it.model, createdAt = it.createdAt,
                updatedAt = it.updatedAt, pinned = it.pinned, revision = it.revision)
        }
    }

    private fun summary(session: Session) = SessionSummary(
        session.id, session.title, session.model, session.createdAt, session.updatedAt,
        session.pinned, session.revision, session.messages.size,
        session.messages.lastOrNull()?.content?.take(160).orEmpty(),
        session.messages.any { message ->
            message.imageUrls.isNotEmpty() || message.attachments.any { it.kind == AttachmentKind.IMAGE }
        }
    )

    private fun snapshot(session: Session) = session.copy(messages = ArrayList(session.messages))
    private fun primary(id: String): File {
        val file = File(paths.sessions, "$id.json")
        if (file.canonicalFile.parentFile != paths.sessions.canonicalFile) {
            throw SessionStorageException("会话文件路径无效")
        }
        return file
    }
    private fun tombstone(id: String) = File(paths.sessions, "$id.deleted")
    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
    private fun validateId(id: String) {
        require(validId(id)) { "无效的会话标识" }
    }

    private fun report(issue: StorageIssue) {
        _issues.update { (it.filterNot { old -> old.sessionId == issue.sessionId && old.message == issue.message } + issue).takeLast(100) }
    }

    private fun trimCacheLocked() {
        val inactive = entries.filterValues {
            it.owners == 0 && !it.loading && !it.dirty &&
                it.flow.subscriptionCount.value == 0 && it.loadState.subscriptionCount.value == 0
        }
        var count = inactive.size
        var bytes = inactive.values.sumOf { estimateBytes(it.current) }
        for ((id, value) in inactive) {
            if (count <= maxInactiveSessions && bytes <= maxInactiveBytes) break
            entries.remove(id)
            count--
            bytes -= estimateBytes(value.current)
        }
    }

    private fun estimateBytes(session: Session?): Long = session?.messages?.sumOf { message ->
        message.content.length * 2L + message.imageUrls.sumOf { it.length * 2L } + message.attachments.size * 256L
    } ?: 0

    internal val cachedSessionCount: Int get() = synchronized(cacheGuard) { entries.size }
    fun clearIssues() { _issues.value = emptyList() }
    fun close() = scope.cancel()

    private fun storageMessage(error: Exception): String =
        (error as? SessionStorageException)?.userMessage ?: "会话存储操作失败，请重试；原文件已保留"

    @Serializable
    private data class SummaryRecord(val summary: SessionSummary, val size: Long, val modifiedAt: Long)

    companion object {
        private const val MAX_SESSION_BYTES = 64 * 1024 * 1024
    }
}
