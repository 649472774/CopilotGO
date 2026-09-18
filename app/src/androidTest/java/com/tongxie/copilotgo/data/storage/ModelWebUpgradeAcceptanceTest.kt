package com.tongxie.copilotgo.data.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tongxie.copilotgo.CopilotGoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Reads only the explicitly staged synthetic predecessor fixture; never seeds or replaces data. */
@RunWith(AndroidJUnit4::class)
class ModelWebUpgradeAcceptanceTest {
    @Test
    fun preservedHistoryAttachmentAndConsentSurviveTheSignedUpgrade() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("modelWebUpgradeFixture") == "code37-to38-synthetic")
        val sessionHash = requireNotNull(arguments.getString("modelWebSessionSha256"))
        val attachmentHash = requireNotNull(arguments.getString("modelWebAttachmentSha256"))
        require(listOf(sessionHash, attachmentHash).all { it.matches(Regex("[a-f0-9]{64}")) })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.tongxie.copilotgo.debug", context.packageName)
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(38L, installed.longVersionCode)
        val app = context.applicationContext as CopilotGoApp
        val paths = app.container.paths
        withContext(Dispatchers.IO) {
            val file = File(paths.sessions, "model-web-upgrade-fixture.json")
            assertEquals(sessionHash, boundedHash(file))
            val blob = File(paths.attachments, "$attachmentHash.blob")
            assertEquals(attachmentHash, boundedHash(blob))
            val session = requireNotNull(app.container.sessionStore.getSession("model-web-upgrade-fixture"))
            assertEquals("Synthetic upgrade preservation", session.title)
            assertTrue(session.pinned)
            assertEquals(2, session.messages.size)
            assertEquals("Synthetic preserved question", session.messages.first().content)
            assertEquals("Synthetic preserved answer", session.messages.last().content)
            val attachment = session.messages.first().attachments.single()
            assertEquals(attachmentHash, attachment.id)
            assertEquals(
                "Synthetic attachment retained across the signed CopilotGO update.",
                app.container.sessionStore.attachments.readText(attachment).trimEnd('\r', '\n')
            )
            assertFalse(session.agentSettings.enabled)
            assertTrue(session.agentSettings.automaticWebSearch)
            val web = app.container.toolSettings.awaitReady().web
            assertFalse(web.externalSharingConsent)
            assertFalse(web.automaticSearchConsent)
            assertEquals("Opening must not rewrite the preserved primary", sessionHash, boundedHash(file))
            assertEquals(attachmentHash, boundedHash(blob))
        }
    }

    private fun boundedHash(file: File): String {
        require(file.isFile && file.length() in 1..64 * 1024)
        return MessageDigest.getInstance("SHA-256").digest(AtomicFiles.read(file, 64 * 1024))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
