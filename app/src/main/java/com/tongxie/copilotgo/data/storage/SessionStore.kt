package com.tongxie.copilotgo.data.storage

import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** 把会话列表落到 App 私有目录的 JSON 文件 */
class SessionStore(
    private val paths: AppPaths,
    private val json: Json
) {
    private val mutex = Mutex()
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = paths.sessions
            val files = dir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()
            val parsed = files.mapNotNull {
                runCatching { json.decodeFromString(Session.serializer(), it.readText()) }
                    .onFailure { e -> Logger.w("Failed to load session ${it.name}: ${e.message}") }
                    .getOrNull()
            }.sortedByDescending { it.updatedAt }
            _sessions.value = parsed
        }
    }

    suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        mutex.withLock {
            session.updatedAt = System.currentTimeMillis()
            val file = File(paths.sessions, "${session.id}.json")
            val tmp = File(paths.sessions, "${session.id}.json.tmp")
            // 原子写：先写到 .tmp，再 rename。进程 mid-write 被杀也只是丢 .tmp，
            // 不会把已存在的 <id>.json 截断成"无法解析的半截 JSON"导致整段会话被 load() 丢掉。
            val payload = json.encodeToString(Session.serializer(), session)
            tmp.outputStream().use { fos ->
                fos.write(payload.toByteArray(Charsets.UTF_8))
                runCatching { fos.fd.sync() }
            }
            if (!tmp.renameTo(file)) {
                // 兼容某些 FS rename 覆盖失败：删旧 + 再 rename，最后兜底直接 writeText
                file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(payload)
                    tmp.delete()
                }
            }
            val current = _sessions.value.toMutableList()
            val idx = current.indexOfFirst { it.id == session.id }
            if (idx >= 0) current[idx] = session else current.add(0, session)
            _sessions.value = current.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(paths.sessions, "$sessionId.json").delete()
            File(paths.sessions, "$sessionId.json.tmp").delete()
            _sessions.value = _sessions.value.filterNot { it.id == sessionId }
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            paths.sessions.listFiles()?.forEach { it.delete() }
            _sessions.value = emptyList()
        }
    }
}
