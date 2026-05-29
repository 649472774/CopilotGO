package com.tongxie.copilotgo.data.auth

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 持久化 GitHub user token 与 Copilot session token。
 * V1 用 DataStore 明文存（仅本地、卸载即清），后续可换 EncryptedSharedPreferences。
 */
class TokenStore(private val context: Context) {

    private val ds get() = context.tokenDataStore

    suspend fun saveGithubToken(token: String) {
        ds.edit { it[KEY_GH_TOKEN] = token }
    }

    suspend fun getGithubToken(): String? =
        ds.data.map { it[KEY_GH_TOKEN] }.first()

    suspend fun saveCopilotToken(token: String, expiresAt: Long, sku: String?, apiBase: String?) {
        ds.edit {
            it[KEY_COPILOT_TOKEN] = token
            it[KEY_COPILOT_EXPIRES] = expiresAt
            if (sku != null) it[KEY_COPILOT_SKU] = sku
            if (apiBase != null) it[KEY_COPILOT_API_BASE] = apiBase
        }
    }

    suspend fun getCopilotToken(): CachedCopilot? {
        val snap = ds.data.first()
        val tok = snap[KEY_COPILOT_TOKEN] ?: return null
        val exp = snap[KEY_COPILOT_EXPIRES] ?: 0L
        val sku = snap[KEY_COPILOT_SKU]
        val apiBase = snap[KEY_COPILOT_API_BASE]
        return CachedCopilot(tok, exp, sku, apiBase)
    }

    val githubTokenFlow: Flow<String?> = ds.data.map { it[KEY_GH_TOKEN] }

    suspend fun clearAll() {
        ds.edit { it.clear() }
    }

    data class CachedCopilot(val token: String, val expiresAt: Long, val sku: String?, val apiBase: String?) {
        fun isExpiringSoon(skewSeconds: Long = 60): Boolean {
            val nowSec = System.currentTimeMillis() / 1000
            return expiresAt - nowSec < skewSeconds
        }
    }

    companion object {
        private val KEY_GH_TOKEN = stringPreferencesKey("gh_token")
        private val KEY_COPILOT_TOKEN = stringPreferencesKey("copilot_token")
        private val KEY_COPILOT_EXPIRES = longPreferencesKey("copilot_expires_at")
        private val KEY_COPILOT_SKU = stringPreferencesKey("copilot_sku")
        private val KEY_COPILOT_API_BASE = stringPreferencesKey("copilot_api_base")

        private val Context.tokenDataStore by preferencesDataStore("copilot_token")
    }
}
