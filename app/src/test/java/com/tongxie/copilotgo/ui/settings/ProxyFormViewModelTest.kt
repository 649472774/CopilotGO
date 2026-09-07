package com.tongxie.copilotgo.ui.settings

import androidx.lifecycle.SavedStateHandle
import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.ui.viewmodel.ProxyFormViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyFormViewModelTest {
    private val saved = ProxyConfig(enabled = true, username = "test-user", password = "saved-test-value")

    @Test
    fun draft_password_stays_in_memory_and_is_never_written_to_saved_state() {
        val handle = SavedStateHandle()
        val vm = ProxyFormViewModel(handle)
        vm.receiveSaved(saved)
        vm.edit { it.copy(host = "localhost", portText = "bad-port", password = "unsaved-test-value") }

        assertEquals("unsaved-test-value", vm.state.value.draft?.password)
        val snapshot = snapshot(handle)
        assertFalse(snapshot.values.contains("unsaved-test-value"))
        assertFalse(snapshot.values.contains("saved-test-value"))

        val restored = ProxyFormViewModel(SavedStateHandle(snapshot))
        restored.receiveSaved(saved)
        val restoredDraft = requireNotNull(restored.state.value.draft)
        assertEquals("localhost", restoredDraft.host)
        assertEquals("bad-port", restoredDraft.portText)
        assertEquals("", restoredDraft.password)
        assertTrue(restoredDraft.passwordNeedsReentry)
        assertTrue(restored.state.value.dirty)
        assertNull(restoredDraft.validatedConfig())
    }

    @Test
    fun untouched_saved_password_is_restored_from_config_not_from_saved_state() {
        val handle = SavedStateHandle()
        val vm = ProxyFormViewModel(handle)
        vm.receiveSaved(saved)
        vm.edit { it.copy(portText = "8080") }

        val restored = ProxyFormViewModel(SavedStateHandle(snapshot(handle)))
        restored.receiveSaved(saved)
        assertEquals("saved-test-value", restored.state.value.draft?.password)
        assertFalse(requireNotNull(restored.state.value.draft).passwordNeedsReentry)
        assertEquals("8080", restored.state.value.draft?.portText)
    }

    @Test
    fun intentionally_cleared_password_is_not_replaced_with_previously_saved_value() {
        val handle = SavedStateHandle()
        val vm = ProxyFormViewModel(handle)
        vm.receiveSaved(saved)
        vm.edit { it.copy(password = "") }

        val restored = ProxyFormViewModel(SavedStateHandle(snapshot(handle)))
        restored.receiveSaved(saved)
        val draft = requireNotNull(restored.state.value.draft)
        assertEquals("", draft.password)
        assertFalse(draft.passwordNeedsReentry)
        assertNotNull(draft.validatedConfig())
    }

    @Test
    fun hidden_lost_password_still_requires_reentry_when_authentication_is_reenabled() {
        val handle = SavedStateHandle()
        val vm = ProxyFormViewModel(handle)
        vm.receiveSaved(saved.copy(username = "", password = ""))
        vm.edit { it.copy(username = "test-user", password = "unsaved-test-value", authenticationEnabled = false) }

        val restored = ProxyFormViewModel(SavedStateHandle(snapshot(handle)))
        restored.receiveSaved(saved.copy(username = "", password = ""))
        restored.edit { it.copy(authenticationEnabled = true) }
        assertTrue(requireNotNull(restored.state.value.draft).validation.passwordMissing)
    }

    @Test
    fun reset_restores_saved_config_and_removes_nonsecret_saved_draft() {
        val handle = SavedStateHandle()
        val vm = ProxyFormViewModel(handle)
        vm.receiveSaved(saved)
        vm.edit { it.copy(portText = "invalid") }
        assertTrue(vm.state.value.dirty)
        vm.reset()
        assertFalse(vm.state.value.dirty)
        assertEquals(ProxyDraft.from(saved), vm.state.value.draft)
        assertTrue(handle.keys().isEmpty())
    }

    @Test
    fun large_pastes_remain_in_memory_but_cannot_create_large_saved_state_strings() {
        val handle = SavedStateHandle()
        val vm = ProxyFormViewModel(handle)
        val pasted = "汉😀".repeat(40_000)
        vm.receiveSaved(saved)
        vm.edit { it.copy(host = pasted, portText = pasted, username = pasted) }
        assertEquals(pasted.length, vm.state.value.draft?.host?.length)
        assertTrue(requireNotNull(vm.state.value.draft).hasOversizedRestorationText)
        val snapshot = snapshot(handle)
        assertTrue(snapshot.values.filterIsInstance<String>().all { it.length <= PROXY_SAVED_TEXT_LIMIT })

        val restored = ProxyFormViewModel(SavedStateHandle(snapshot))
        restored.receiveSaved(saved)
        val draft = requireNotNull(restored.state.value.draft)
        assertEquals("", draft.host)
        assertEquals("", draft.portText)
        assertEquals("", draft.username)
        assertTrue(restored.state.value.restoredTextOmitted)
        assertTrue(restored.state.value.dirty)
        assertNull(draft.validatedConfig())
    }

    @Test
    fun saved_state_boundary_is_exact_and_never_truncates_a_malformed_port_into_valid_text() {
        val handle = SavedStateHandle()
        val vm = ProxyFormViewModel(handle)
        vm.receiveSaved(saved)
        val boundary = "a".repeat(PROXY_SAVED_TEXT_LIMIT)
        vm.edit { it.copy(host = boundary, portText = "7890" + "x".repeat(PROXY_SAVED_TEXT_LIMIT)) }
        val restored = ProxyFormViewModel(SavedStateHandle(snapshot(handle)))
        restored.receiveSaved(saved)
        assertEquals(boundary, restored.state.value.draft?.host)
        assertEquals("", restored.state.value.draft?.portText)
        assertNull(restored.state.value.draft?.validatedConfig())
    }

    private fun snapshot(handle: SavedStateHandle): Map<String, Any?> =
        handle.keys().associateWith { handle.get<Any?>(it) }
}
