package com.tongxie.copilotgo.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun normalize_trims_and_strips_leading_v() {
        assertEquals("0.1.30", UpdateChecker.normalizeVersion("  v0.1.30 "))
        assertEquals("0.1.30", UpdateChecker.normalizeVersion("V0.1.30"))
        assertEquals("0.1.30", UpdateChecker.normalizeVersion("0.1.30"))
    }

    @Test
    fun normalize_preserves_prerelease_suffix() {
        assertEquals("1.2.3-debug", UpdateChecker.normalizeVersion("v1.2.3-debug"))
        assertEquals("1.0.0-beta", UpdateChecker.normalizeVersion("1.0.0-beta"))
    }

    @Test
    fun compare_numeric_segments_not_lexical() {
        assertTrue(UpdateChecker.compareVersions("0.1.10", "0.1.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("0.1.9", "0.1.10") < 0)
    }

    @Test
    fun compare_equal_versions() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.0", "1.0.0"))
    }

    @Test
    fun compare_handles_differing_segment_counts() {
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
        assertTrue(UpdateChecker.compareVersions("1.2.3.4", "1.2.3") > 0)
    }

    @Test
    fun prerelease_ranks_lower_than_stable_same_core() {
        assertTrue(UpdateChecker.compareVersions("1.0.0-beta", "1.0.0") < 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "1.0.0-rc") > 0)
    }

    @Test
    fun prerelease_identifiers_compared_lexically_as_tiebreak() {
        assertTrue(UpdateChecker.compareVersions("1.0.0-beta", "1.0.0-rc") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.0.0-beta", "1.0.0-beta"))
    }

    @Test
    fun compare_rejects_malformed_versions_instead_of_inventing_zero_segments() {
        assertThrows(IllegalArgumentException::class.java) { UpdateChecker.compareVersions("1.a.3", "1.0.3") }
        assertThrows(IllegalArgumentException::class.java) { UpdateChecker.compareVersions("", "1.0.0") }
        assertThrows(IllegalArgumentException::class.java) { UpdateChecker.compareVersions("release", "1.0.0") }
    }

    @Test
    fun prerelease_numbers_and_build_metadata_follow_semantic_ordering() {
        assertTrue(UpdateChecker.compareVersions("1.0.0-beta.10", "1.0.0-beta.2") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0-2", "1.0.0-beta") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.0.0+build.9", "1.0.0+build.10"))
        assertTrue(UpdateChecker.compareVersions("99999999999999999.0.0", "2.0.0") > 0)
    }

    @Test
    fun checksum_selects_exact_filename_and_rejects_ambiguous_records() {
        val hash = "a".repeat(64)
        assertEquals(hash, UpdateChecker.parseChecksum("$hash  other.apk\n$hash *app.apk\n", "app.apk"))
        assertEquals(hash, UpdateChecker.parseChecksum(hash.uppercase(), "app.apk"))
        assertThrows(UpdateException::class.java) {
            UpdateChecker.parseChecksum("$hash  app.apk\n$hash  app.apk", "app.apk")
        }
        assertThrows(UpdateException::class.java) {
            UpdateChecker.parseChecksum("$hash  other.apk", "app.apk")
        }
        assertThrows(UpdateException::class.java) { UpdateChecker.requireSha256("a".repeat(63)) }
    }
}
