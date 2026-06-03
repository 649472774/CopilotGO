package com.tongxie.copilotgo.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun compare_does_not_throw_on_malformed_segments() {
        // Malformed parts are treated as 0, never throwing.
        assertEquals(0, UpdateChecker.compareVersions("1.a.3", "1.0.3"))
        assertTrue(UpdateChecker.compareVersions("1.2.x", "1.1.0") > 0)
    }
}
