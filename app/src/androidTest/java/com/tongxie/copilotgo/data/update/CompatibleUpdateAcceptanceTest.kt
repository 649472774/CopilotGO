package com.tongxie.copilotgo.data.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

/** Opt-in, read-only acceptance of an externally staged signed APK; never installs or cleans up. */
@RunWith(AndroidJUnit4::class)
class CompatibleUpdateAcceptanceTest {
    @Test
    fun accepts_explicit_code35_candidate_while_code34_remains_installed() {
        val fixture = explicitFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("This fixture is only for the existing debug installation", PACKAGE_NAME, context.packageName)

        runBlocking {
            withTimeout(60_000) {
                withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "updates")
                    val realDirectory = File(context.cacheDir.canonicalFile, "updates")
                    require(fixture.apk.parentFile == directory || fixture.apk.parentFile == realDirectory) {
                        "candidateApk must be directly inside ${directory.absolutePath}"
                    }
                    require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) && directory.canonicalFile == realDirectory) {
                        "The target updates directory must exist and must not be a symbolic link"
                    }
                    val before = attributes(fixture.apk)
                    require(before.isRegularFile && before.size() in 1..UpdateLimits().maxApkBytes) {
                        "The candidate must be a regular, non-symlink APK between 1 byte and 128 MiB"
                    }
                    require(fixture.apk.canonicalFile.parentFile == realDirectory) {
                        "The candidate resolves outside the target updates directory"
                    }

                    val manager = context.packageManager
                    assertEquals("Run this acceptance before replacing code 34", 34L, installedPackage(manager).longVersionCode)
                    val archive = requireNotNull(archivePackage(manager, fixture.apk)) { "The candidate is not an APK" }
                    assertEquals("Unexpected candidate package", PACKAGE_NAME, archive.packageName)
                    assertEquals("Unexpected candidate versionCode", fixture.versionCode, archive.longVersionCode)
                    assertEquals("Unexpected candidate versionName", "${fixture.versionName}-debug", archive.versionName)

                    val info = UpdateInfo(
                        versionName = fixture.versionName,
                        tagName = "",
                        releaseName = "Explicit local signed upgrade fixture",
                        changelog = "",
                        apkUrl = "",
                        apkSize = before.size(),
                        releasePageUrl = "",
                        apkName = fixture.apk.name,
                        apkSha256 = fixture.sha256
                    )
                    // The real verifier bounds hashing by apkSize and compares the current signing identity.
                    AndroidUpdatePlatform(context).verifyApk(info, fixture.apk)

                    val after = attributes(fixture.apk)
                    require(after.isRegularFile) { "The candidate was replaced during verification" }
                    assertEquals("Candidate length changed", before.size(), after.size())
                    assertEquals("Candidate modification time changed", before.lastModifiedTime(), after.lastModifiedTime())
                    assertEquals("Candidate file identity changed", before.fileKey(), after.fileKey())
                    assertEquals("Verification must not install the candidate", 34L, installedPackage(manager).longVersionCode)
                }
            }
        }
    }

    private fun explicitFixture(): Fixture {
        val arguments = InstrumentationRegistry.getArguments()
        val names = listOf("compatibleUpdateFixture", "candidateApk", "candidateSha256", "candidateVersion", "candidateVersionCode")
        assumeTrue(
            "Compatible-update acceptance is opt-in; no fixture arguments were supplied",
            names.any(arguments::containsKey)
        )
        val values = names.associateWith { name ->
            requireNotNull(arguments.getString(name)?.takeIf(String::isNotBlank)) {
                "Supply all five compatible-update fixture arguments; missing $name"
            }
        }
        require(values.getValue("compatibleUpdateFixture") == "phase1-code34-to35") { "Invalid fixture marker" }
        require(values.getValue("candidateVersion") == "0.2.0") { "This fixture only accepts version 0.2.0" }
        require(values.getValue("candidateVersionCode") == "35") { "This fixture only accepts versionCode 35" }
        val sha256 = values.getValue("candidateSha256")
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "candidateSha256 must contain exactly 64 hexadecimal characters" }
        val path = values.getValue("candidateApk")
        require(path.length <= 1024 && path == path.trim()) { "Invalid candidateApk path" }
        val apk = File(path)
        require(apk.isAbsolute && apk.toPath().normalize() == apk.toPath()) { "candidateApk must be absolute without traversal" }
        require(apk.name.matches(Regex("CopilotGo-update-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\\.apk"))) {
            "Use the updater cache filename CopilotGo-update-<lowercase UUID>.apk"
        }
        return Fixture(apk, sha256.lowercase(), values.getValue("candidateVersion"), values.getValue("candidateVersionCode").toLong())
    }

    private fun attributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    @Suppress("DEPRECATION")
    private fun installedPackage(manager: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
        } else {
            manager.getPackageInfo(PACKAGE_NAME, 0)
        }

    @Suppress("DEPRECATION")
    private fun archivePackage(manager: PackageManager, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            manager.getPackageArchiveInfo(file.absolutePath, 0)
        }

    private data class Fixture(val apk: File, val sha256: String, val versionName: String, val versionCode: Long)

    companion object {
        private const val PACKAGE_NAME = "com.tongxie.copilotgo.debug"
    }
}
