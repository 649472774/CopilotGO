package com.tongxie.copilotgo.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tongxie.copilotgo.data.storage.CredentialVault
import com.tongxie.copilotgo.data.storage.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TokenStore(
    private val legacy: DataStore<Preferences>,
    private val vault: SecretVault
) : CredentialStore {
    constructor(context: Context) : this(
        context.applicationContext.tokenDataStore, CredentialVault(context)
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val github = MutableStateFlow<String?>(null)

    override suspend fun readCredentials(): StoredCredentials = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked() }
    }

    override suspend fun writeCredentials(credentials: StoredCredentials) = withContext(Dispatchers.IO) {
        mutex.withLock { writeLocked(credentials) }
    }

    private suspend fun readLocked(): StoredCredentials {
        val encrypted = vault.read(NAMESPACE)
        if (encrypted != null) {
            val credentials = json.decodeFromString(StoredCredentials.serializer(), encrypted)
            clearLegacy()
            github.value = credentials.githubToken
            return credentials
        }
        val snapshot = legacy.data.first()
        val cached = snapshot[KEY_COPILOT_TOKEN]?.let { token ->
            CachedCopilot(
                token, snapshot[KEY_COPILOT_EXPIRES] ?: 0,
                snapshot[KEY_COPILOT_SKU], snapshot[KEY_COPILOT_API_BASE]
            )
        }
        val credentials = StoredCredentials(snapshot[KEY_GH_TOKEN], cached)
        writeLocked(credentials)
        return credentials
    }

    private suspend fun writeLocked(credentials: StoredCredentials) {
        val payload = json.encodeToString(StoredCredentials.serializer(), credentials)
        vault.write(NAMESPACE, payload)
        check(vault.read(NAMESPACE) == payload) { "登录凭据写入校验失败" }
        clearLegacy()
        github.value = credentials.githubToken
    }

    private suspend fun clearLegacy() {
        val snapshot = legacy.data.first()
        if (snapshot.asMap().keys.none { it in LEGACY_KEYS }) return
        legacy.edit { preferences -> LEGACY_KEYS.forEach { preferences.remove(it) } }
    }

    suspend fun saveGithubToken(token: String) = withContext(Dispatchers.IO) {
        mutex.withLock { writeLocked(readLocked().copy(githubToken = token)) }
    }

    suspend fun getGithubToken(): String? = readCredentials().githubToken

    suspend fun saveCopilotToken(token: String, expiresAt: Long, sku: String?, apiBase: String?) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeLocked(readLocked().copy(copilot = CachedCopilot(token, expiresAt, sku, apiBase)))
            }
        }

    suspend fun getCopilotToken(): CachedCopilot? = readCredentials().copilot

    val githubTokenFlow: Flow<String?> = flow {
        readCredentials()
        emitAll(github)
    }

    // Keep an encrypted empty record so a cancelled legacy cleanup cannot resurrect login.
    override suspend fun clearAll() = writeCredentials(StoredCredentials())

    @Serializable
    data class CachedCopilot(val token: String, val expiresAt: Long, val sku: String?, val apiBase: String?) {
        fun isExpiringSoon(skewSeconds: Long = 60): Boolean =
            token.isBlank() || expiresAt - System.currentTimeMillis() / 1000 < skewSeconds
    }

    companion object {
        private const val NAMESPACE = "copilot-token"
        private val KEY_GH_TOKEN = stringPreferencesKey("gh_token")
        private val KEY_COPILOT_TOKEN = stringPreferencesKey("copilot_token")
        private val KEY_COPILOT_EXPIRES = longPreferencesKey("copilot_expires_at")
        private val KEY_COPILOT_SKU = stringPreferencesKey("copilot_sku")
        private val KEY_COPILOT_API_BASE = stringPreferencesKey("copilot_api_base")
        private val LEGACY_KEYS = setOf(
            KEY_GH_TOKEN, KEY_COPILOT_TOKEN, KEY_COPILOT_EXPIRES, KEY_COPILOT_SKU, KEY_COPILOT_API_BASE
        )
        private val Context.tokenDataStore by preferencesDataStore("copilot_token")
    }
}
