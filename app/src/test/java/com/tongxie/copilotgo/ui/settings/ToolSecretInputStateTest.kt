package com.tongxie.copilotgo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSecretInputStateTest {
    @Test fun oversizedOrMultilineInputNeverBecomesATruncatedCredential() {
        val state = ToolSecretInputState(16)
        state.edit("synthetic")
        state.edit("x".repeat(17))
        assertEquals("synthetic", state.value)
        assertTrue(state.rejectedInput)
        state.edit("synthetic\nheader")
        assertEquals("synthetic", state.value)
        assertTrue(state.rejectedInput)
        state.edit("x".repeat(16))
        assertEquals(16, state.value.length)
        assertFalse(state.rejectedInput)
    }

    @Test fun backgroundClearsOnlyUnsavedInputAndRequiresReentry() {
        val state = ToolSecretInputState(64)
        state.edit("synthetic-test-key")
        state.clear(forBackground = true)
        assertEquals("", state.value)
        assertTrue(state.needsReentry)
        state.clear(forBackground = true)
        assertTrue(state.needsReentry)
        state.edit("replacement-fixture")
        assertFalse(state.needsReentry)
    }

    @Test fun savingOrDisposalClearsInputWithoutRestoringIt() {
        val state = ToolSecretInputState(64)
        state.edit("synthetic-test-key")
        state.clear()
        assertEquals("", state.value)
        assertFalse(state.needsReentry)
        assertFalse(state.rejectedInput)
        state.clear(forBackground = true)
        assertFalse(state.needsReentry)
    }

    @Test fun diagnosticStringDoesNotContainTheSecret() {
        val state = ToolSecretInputState(64)
        state.edit("synthetic-test-key")
        assertEquals("ToolSecretInputState(redacted)", state.toString())
        assertFalse(state.toString().contains(state.value))
    }
}
