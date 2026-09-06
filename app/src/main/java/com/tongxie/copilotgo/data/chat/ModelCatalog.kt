package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.net.networkErrorMessage
import com.tongxie.copilotgo.data.storage.AtomicFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

data class ModelCatalogState(
    val models: List<ModelInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val isStale: Boolean = false,
    val updatedAt: Long? = null
)

class ModelUnavailableException(message: String) : IOException(message)

class ModelCatalog(
    private val client: CopilotChatClient,
    private val json: Json,
    private val auth: AuthRepository,
    private val cacheFile: File? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ModelCatalogState())
    val state = _state.asStateFlow()
    private var owner: String? = null
    private var observedGeneration = auth.accountGeneration.value

    init {
        scope.launch {
            auth.accountGeneration.collect { generation ->
                if (generation != observedGeneration) {
                    observedGeneration = generation
                    _state.value = ModelCatalogState()
                }
            }
        }
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val generation = auth.accountGeneration.value
            val prior = state.value
            if (!force && !prior.isStale && prior.error == null && prior.updatedAt != null &&
                clock() - prior.updatedAt in 0 until REFRESH_INTERVAL_MS
            ) return@withLock
            _state.value = prior.copy(loading = true, error = null)
            try {
                val key = auth.accountCacheKey()
                if (owner != key) {
                    owner = key
                    _state.value = ModelCatalogState(loading = true)
                    loadCache(key)
                }
                val models = client.listModels()
                if (generation != auth.accountGeneration.value) throw CancellationException("Account changed")
                val refreshed = ModelCatalogState(models = models, updatedAt = clock())
                _state.value = refreshed
                if (cacheFile != null) {
                    try {
                        AtomicFiles.write(cacheFile, json.encodeToString(
                            CachedCatalog.serializer(), CachedCatalog(key, models, refreshed.updatedAt!!)
                        ).toByteArray())
                    } catch (_: IOException) {
                        _state.value = refreshed.copy(error = "模型已获取，但本地缓存写入失败")
                    }
                }
            } catch (e: CancellationException) {
                if (generation == auth.accountGeneration.value) {
                    _state.value = state.value.copy(loading = false, isStale = true)
                } else {
                    _state.value = ModelCatalogState()
                }
                throw e
            } catch (e: Exception) {
                if (generation == auth.accountGeneration.value) {
                    _state.value = state.value.copy(
                        loading = false, isStale = true, error = networkErrorMessage(e)
                    )
                }
            }
        }
    }

    private fun loadCache(key: String) {
        val file = cacheFile ?: return
        if (!file.exists()) return
        try {
            val cached = json.decodeFromString(
                CachedCatalog.serializer(), AtomicFiles.read(file, 1024 * 1024).toString(Charsets.UTF_8)
            )
            if (cached.owner == key && clock() - cached.updatedAt in 0..MAX_CACHE_AGE_MS) {
                _state.value = ModelCatalogState(
                    cached.models.filter { it.chatCompatible }, loading = true,
                    isStale = true, updatedAt = cached.updatedAt
                )
            }
        } catch (_: IOException) {
            _state.value = state.value.copy(error = "模型缓存无法读取，正在重新获取")
        } catch (_: SerializationException) {
            _state.value = state.value.copy(error = "模型缓存已损坏，正在重新获取")
        }
    }

    suspend fun requireModel(selectedId: String, needsVision: Boolean): ModelInfo {
        refresh()
        val current = state.value
        if (current.isStale || current.updatedAt == null) {
            throw ModelUnavailableException(current.error ?: "请先刷新可用模型列表")
        }
        val model = if (selectedId.isBlank()) {
            current.models.firstOrNull { it.isChatDefault && (!needsVision || it.supportsVision) }
                ?: current.models.firstOrNull { !needsVision || it.supportsVision }
        } else {
            current.models.firstOrNull { it.id == selectedId }
        } ?: throw ModelUnavailableException(
            if (selectedId.isBlank()) "当前账号没有可用的聊天模型，请刷新或检查订阅"
            else "已选模型当前不可用，请刷新列表并手动选择模型"
        )
        if (needsVision && !model.supportsVision) {
            throw ModelUnavailableException("此会话包含图片，请选择支持视觉的模型后继续")
        }
        return model
    }

    fun close() = scope.cancel()

    @Serializable
    private data class CachedCatalog(val owner: String, val models: List<ModelInfo>, val updatedAt: Long)

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L
        private const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
