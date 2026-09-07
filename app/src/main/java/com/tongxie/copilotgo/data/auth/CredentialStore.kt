package com.tongxie.copilotgo.data.auth

import kotlinx.serialization.Serializable

@Serializable
data class StoredCredentials(
    val githubToken: String? = null,
    val copilot: TokenStore.CachedCopilot? = null
)

/** A whole credential pair is committed or cleared in one durable operation. */
interface CredentialStore {
    suspend fun readCredentials(): StoredCredentials
    suspend fun writeCredentials(credentials: StoredCredentials)
    suspend fun clearAll() = writeCredentials(StoredCredentials())
}
