package com.tongxie.copilotgo.data.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tongxie.copilotgo.data.auth.TokenStore
import com.tongxie.copilotgo.data.proxy.ProxySettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Native complement to portable JVM fixtures; run on the integration owner's fixture devices. */
@RunWith(AndroidJUnit4::class)
class CredentialMigrationInstrumentedTest {
    @Test
    fun migratesDefaultAndroidDataStoresToRealKeystoreVaultAndReopens() = runBlocking {
        withFixture { fixture ->
            val expiry = System.currentTimeMillis() / 1000 + 3600
            fixture.tokenLegacy.edit {
                it[stringPreferencesKey("gh_token")] = "fixture-native-github"
                it[stringPreferencesKey("copilot_token")] = "fixture-native-copilot"
                it[longPreferencesKey("copilot_expires_at")] = expiry
                it[stringPreferencesKey("copilot_sku")] = "fixture-sku"
                it[stringPreferencesKey("copilot_api_base")] = "https://copilot.example.invalid"
            }
            fixture.proxyLegacy.edit {
                it[booleanPreferencesKey("enabled")] = true
                it[stringPreferencesKey("type")] = "HTTP"
                it[stringPreferencesKey("host")] = "127.0.0.1"
                it[intPreferencesKey("port")] = 8080
                it[stringPreferencesKey("username")] = "fixture-native-user"
                it[stringPreferencesKey("password")] = "fixture-native-value"
            }
            val tokens = TokenStore(fixture.tokenLegacy, fixture.vault())
            assertEquals("fixture-native-github", tokens.getGithubToken())
            val copilot = requireNotNull(tokens.getCopilotToken())
            assertEquals("fixture-native-copilot", copilot.token)
            assertEquals(expiry, copilot.expiresAt)
            assertEquals("fixture-sku", copilot.sku)
            assertEquals("https://copilot.example.invalid", copilot.apiBase)
            val proxies = ProxySettingsStore(fixture.proxyLegacy, fixture.vault(), fixture.scope)
            withTimeout(15_000) { proxies.initialized.first { it } }
            assertNull(proxies.loadError.value)
            assertTrue(proxies.config.value.enabled)
            assertEquals(8080, proxies.config.value.port)
            assertEquals("fixture-native-user", proxies.config.value.username)
            assertEquals("fixture-native-value", proxies.config.value.password)
            assertTrue(fixture.tokenLegacy.data.first().asMap().isEmpty())
            assertTrue(fixture.proxyLegacy.data.first().asMap().isEmpty())

            val reopened = TokenStore(fixture.tokenLegacy, fixture.vault())
            assertEquals(copilot, reopened.getCopilotToken())
            assertEquals("fixture-native-github", reopened.getGithubToken())
            val reopenedProxy = ProxySettingsStore(fixture.proxyLegacy, fixture.vault(), fixture.scope)
            withTimeout(15_000) { reopenedProxy.initialized.first { it } }
            assertEquals(proxies.config.value, reopenedProxy.config.value)
            val encrypted = requireNotNull(File(fixture.context.noBackupFilesDir, "credentials").listFiles())
                .filter { it.extension == "enc" }
            assertEquals(2, encrypted.size)
            encrypted.forEach {
                assertFalse("Encrypted files must not contain plaintext fixtures",
                    it.readBytes().toString(Charsets.UTF_8).contains("fixture-native"))
            }
            reopened.clearAll()
            assertNull(TokenStore(fixture.tokenLegacy, fixture.vault()).getGithubToken())
            assertTrue(fixture.tokenLegacy.data.first().asMap().isEmpty())
        }
    }

    @Test
    fun corruptNativeVaultDoesNotRestoreLegacyCredentialsOrResetCiphertext() = runBlocking {
        withFixture { fixture ->
            val legacyKey = stringPreferencesKey("gh_token")
            fixture.tokenLegacy.edit { it[legacyKey] = "fixture-native-original" }
            val tokens = TokenStore(fixture.tokenLegacy, fixture.vault())
            assertEquals("fixture-native-original", tokens.getGithubToken())
            fixture.tokenLegacy.edit { it[legacyKey] = "fixture-native-must-not-be-imported" }
            val file = File(File(fixture.context.noBackupFilesDir, "credentials"), "copilot-token.enc")
            val corrupted = file.readBytes().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            file.writeBytes(corrupted)
            var rejected = false
            try {
                TokenStore(fixture.tokenLegacy, fixture.vault()).getGithubToken()
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("Corrupt encrypted data must be an explicit failure", rejected)
            assertEquals("fixture-native-must-not-be-imported", fixture.tokenLegacy.data.first()[legacyKey])
            assertArrayEquals(corrupted, file.readBytes())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) = withContext(Dispatchers.IO) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(target.noBackupFilesDir, "core-credential-migration-$id")
        check(root.mkdirs())
        val alias = "copilotgo.fixture.$id"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!keyStore.containsAlias(alias))
        val fixture = Fixture(target, root, alias)
        try {
            block(fixture)
        } finally {
            fixture.scope.coroutineContext[Job]?.cancelAndJoin()
            keyStore.deleteEntry(alias)
            check(root.parentFile?.canonicalFile == target.noBackupFilesDir.canonicalFile)
            check(root.name == "core-credential-migration-$id")
            check(root.deleteRecursively())
        }
    }

    private class Fixture(target: Context, val root: File, private val alias: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, "no-backup")
            override fun getFilesDir(): File = File(root, "files")
        }
        // Keep the default Android backend (FileStorage in the release dependency set).
        val tokenLegacy = PreferenceDataStoreFactory.create(scope = scope) {
            File(root, "token-legacy.preferences_pb")
        }
        val proxyLegacy = PreferenceDataStoreFactory.create(scope = scope) {
            File(root, "proxy-legacy.preferences_pb")
        }
        fun vault() = CredentialVault(context, keyAlias = alias)
    }
}
