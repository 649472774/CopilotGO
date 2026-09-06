package com.tongxie.copilotgo.data.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tongxie.copilotgo.data.storage.SecretVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import okio.Path.Companion.toPath

// Keep real JVM persistence without the default FileStorage's Windows rename regression.
class TokenMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun legacyCredentialsAreClearedOnlyAfterVerifiedEncryptedWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) {
                File(temporary.root, "success.preferences_pb").absolutePath.toPath()
            }
            dataStore.edit { it[stringPreferencesKey("gh_token")] = "fixture-legacy" }
            val vault = MemoryVault()
            val store = TokenStore(dataStore, vault)
            assertEquals("fixture-legacy", store.getGithubToken())
            assertTrue(dataStore.data.first().asMap().isEmpty())
            assertTrue(vault.values.values.single().contains("fixture-legacy"))
            store.clearAll()
            assertNull(store.getGithubToken())
            assertTrue("An empty encrypted record prevents legacy resurrection", vault.values.isNotEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun migrationFailurePreservesLegacyCredentials() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) {
                File(temporary.root, "failure.preferences_pb").absolutePath.toPath()
            }
            val key = stringPreferencesKey("gh_token")
            dataStore.edit { it[key] = "fixture-legacy" }
            val vault = MemoryVault().apply { fail = true }
            try {
                TokenStore(dataStore, vault).getGithubToken()
                fail("Migration must surface failure")
            } catch (_: IOException) {
                assertEquals("fixture-legacy", dataStore.data.first()[key])
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingEncryptedKeyDoesNotFallBackToPlaintext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) {
                File(temporary.root, "corrupt.preferences_pb").absolutePath.toPath()
            }
            dataStore.edit { it[stringPreferencesKey("gh_token")] = "fixture-legacy" }
            val broken = object : SecretVault {
                override suspend fun read(name: String): String? = throw IOException("fixture key unavailable")
                override suspend fun write(name: String, value: String) = fail("Must not reset credentials")
            }
            try {
                TokenStore(dataStore, broken).getGithubToken()
                fail("Expected encrypted credential failure")
            } catch (_: IOException) {
                assertFalse(dataStore.data.first().asMap().isEmpty())
            }
        } finally {
            scope.cancel()
        }
    }
}

internal class MemoryVault : SecretVault {
    val values = mutableMapOf<String, String>()
    var fail = false
    override suspend fun read(name: String) = values[name]
    override suspend fun write(name: String, value: String) {
        if (fail) throw IOException("fixture vault failure")
        values[name] = value
    }
}
