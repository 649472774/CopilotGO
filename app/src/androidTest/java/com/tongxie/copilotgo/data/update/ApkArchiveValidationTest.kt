package com.tongxie.copilotgo.data.update

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tongxie.copilotgo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Uses only packaged fixture APKs, never account data or a signing key. */
@RunWith(AndroidJUnit4::class)
class ApkArchiveValidationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun same_installed_apk_is_rejected_as_non_upgrade() = runBlocking {
        withApkFixture(context) { file, info ->
            expectRejection(file, info, "版本不高于")
        }
    }

    @Test
    fun actual_instrumentation_package_is_rejected_as_wrong_application() = runBlocking {
        withApkFixture(instrumentation.context) { file, info ->
            expectRejection(file, info, "其他应用")
        }
    }

    @Test
    fun changed_bytes_are_rejected_before_package_installation() = runBlocking {
        withApkFixture(context) { file, info ->
            withContext(Dispatchers.IO) {
                RandomAccessFile(file, "rw").use { output ->
                    output.seek(0)
                    val first = output.readByte()
                    output.seek(0)
                    output.writeByte(first.toInt() xor 1)
                }
            }
            expectRejection(file, info, "发生变化")
        }
    }

    private suspend fun withApkFixture(sourceContext: Context, block: suspend (File, UpdateInfo) -> Unit) {
        val target = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "CopilotGo-update-${UUID.randomUUID()}.apk").also {
                File(sourceContext.applicationInfo.sourceDir).copyTo(it)
            }
        }
        try {
            val hash = withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                target.inputStream().use { input ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        digest.update(bytes, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            block(target, UpdateInfo(
                versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
                tagName = "fixture",
                releaseName = "Fixture",
                changelog = "",
                apkUrl = "https://github.com/649472774/CopilotGO/releases",
                apkSize = target.length(),
                releasePageUrl = "https://github.com/649472774/CopilotGO/releases",
                apkName = target.name,
                apkSha256 = hash
            ))
        } finally {
            withContext(Dispatchers.IO) { check(target.delete() || !target.exists()) }
        }
    }

    private suspend fun expectRejection(file: File, info: UpdateInfo, reason: String) {
        try {
            AndroidUpdatePlatform(context).verifyApk(info, file)
            fail("Archive should not be installable")
        } catch (expected: UpdateException) {
            assertTrue(expected.message.orEmpty().contains(reason))
        }
    }
}
