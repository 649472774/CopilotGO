package com.tongxie.copilotgo.data.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertThrows
import org.junit.Test

class ApkCompatibilityTest {
    private val installed = ApkIdentity("com.tongxie.copilotgo.debug", 34, "0.1.33-debug", 31, setOf("a".repeat(64)))
    private val update = installed.copy(versionCode = 35, versionName = "0.2.0-debug")

    @Test
    fun preserves_debug_upgrade_identity() {
        ApkCompatibility.requireCompatible(installed, update, "0.2.0", 31)
    }

    @Test
    fun rejects_wrong_package_even_when_name_version_and_digest_are_plausible() {
        reject(update.copy(packageName = "com.tongxie.copilotgo"))
        val error = assertThrows(UpdateException::class.java) {
            ApkCompatibility.requireCompatible(
                installed, update.copy(packageName = "fixture.test", versionName = null), "0.2.0", 31
            )
        }
        org.junit.Assert.assertTrue(error.message.orEmpty().contains("其他应用"))
    }

    @Test
    fun rejects_downgrades_and_equal_version_codes() {
        reject(update.copy(versionCode = 34))
        reject(update.copy(versionCode = 1))
    }

    @Test
    fun rejects_manifest_release_mismatch_and_unsupported_android() {
        reject(update.copy(versionName = "0.3.0-debug"))
        reject(update.copy(versionName = "not-a-version"))
        reject(update.copy(versionName = null))
        reject(update.copy(minSdk = 32))
    }

    @Test
    fun rejects_ephemeral_ci_signers_missing_signers_and_extra_signers() {
        reject(update.copy(signerSha256 = setOf("b".repeat(64))))
        reject(update.copy(signerSha256 = emptySet()))
        reject(update.copy(signerSha256 = installed.signerSha256 + "b".repeat(64)))
        assertThrows(UpdateException::class.java) {
            ApkCompatibility.requireCompatible(installed.copy(signerSha256 = emptySet()), update, "0.2.0", 31)
        }
    }

    @Test
    fun allows_only_https_official_asset_origins_and_repository_assets() {
        GitHubUpdateUrls.requireAsset("https://github.com/649472774/CopilotGO/releases/download/v0.2.0/app.apk".toHttpUrl())
        GitHubUpdateUrls.requireTrusted("https://release-assets.githubusercontent.com/asset?signature=fixture".toHttpUrl())
        listOf(
            "http://github.com/649472774/CopilotGO/releases/download/v0.2.0/app.apk",
            "https://github.com.example.com/asset", "https://github.com:8443/asset",
            "https://user@github.com/asset", "https://github.com/asset#fragment"
        ).forEach { url ->
            assertThrows(UpdateException::class.java) { GitHubUpdateUrls.requireTrusted(url.toHttpUrl()) }
        }
        assertThrows(UpdateException::class.java) {
            GitHubUpdateUrls.requireAsset("https://github.com/another/repository/releases/download/v1/app.apk".toHttpUrl())
        }
    }

    private fun reject(candidate: ApkIdentity) {
        assertThrows(UpdateException::class.java) {
            ApkCompatibility.requireCompatible(installed, candidate, "0.2.0", 31)
        }
    }
}
