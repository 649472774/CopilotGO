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
            file.writeText(json.encodeToString(Session.serializer(), session))
            val current = _sessions.value.toMutableList()
            val idx = current.indexOfFirst { it.id == session.id }
            if (idx >= 0) current[idx] = session else current.add(0, session)
            _sessions.value = current.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(paths.sessions, "$sessionId.json").delete()
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
