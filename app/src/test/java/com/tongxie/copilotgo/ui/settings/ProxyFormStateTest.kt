package com.tongxie.copilotgo.ui.settings

import com.tongxie.copilotgo.data.proxy.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyFormStateTest {
    private val saved = ProxyConfig(enabled = true)
    private val draft = ProxyDraft.from(saved)
    private val initial = ProxyFormState(saved, draft)

    @Test
    fun ports_accept_boundaries_and_preserve_numeric_text_until_save() {
        assertEquals(1, parseProxyPort("1"))
        assertEquals(65535, parseProxyPort("65535"))
        assertEquals(7890, parseProxyPort("007890"))
        val edited = initial.edit(draft.copy(portText = "007890"))
        assertTrue(edited.dirty)
        assertEquals("007890", edited.draft?.portText)
        val config = requireNotNull(edited.draft?.validatedConfig())
        val accepted = edited.accepted(config, leave = false)
        assertFalse(accepted.dirty)
        assertTrue(accepted.savedNotice)
        assertEquals("7890", accepted.draft?.portText)
    }

    @Test
    fun malformed_ports_are_not_filtered_into_valid_looking_numbers() {
        listOf(
            "", "0", "-1", "+7890", "65536", "7890 ", " 7890", "78x90", "7890\n",
            "7.890", "７８９０", "١٢٣", "端口7890", "😀7890", "9".repeat(1000)
        ).forEach { text ->
            assertNull(parseProxyPort(text))
            val edited = initial.edit(draft.copy(portText = text))
            val editedDraft = requireNotNull(edited.draft)
            assertEquals(text, editedDraft.portText)
            assertTrue(edited.dirty)
            assertTrue(editedDraft.validation.portInvalid)
            assertNull(editedDraft.validatedConfig())
        }
    }

    @Test
    fun hosts_support_ascii_names_and_reject_urls_unicode_whitespace_and_long_text() {
        listOf(
            "127.0.0.1", "10.0.2.2", "localhost", "proxy.example.com", "proxy-node.example",
            "::1", "[::1]", "2001:db8::1"
        ).forEach {
            assertNotNull(draft.copy(host = it).validatedConfig())
        }
        listOf(
            "", " ", "127.0.0.1 ", "http://127.0.0.1", "host/path", "host:7890", "a..b",
            "user@host", "[not-an-ip]", "a\\b", "a\tb", "a\nb", "a?b", "a#b",
            "代理.example", "😀.example", "a".repeat(254), "a".repeat(64) + ".example",
            "-host.example", "host-.example", "256.0.0.1", "1.2.3", "1.2.3.4.5"
        ).forEach {
            assertTrue(draft.copy(host = it).validation.hostInvalid)
            assertNull(draft.copy(host = it).validatedConfig())
        }
    }

    @Test
    fun enabling_authentication_without_a_username_is_dirty_and_invalid() {
        val edited = initial.edit(draft.copy(authenticationEnabled = true))
        val editedDraft = requireNotNull(edited.draft)
        assertTrue(edited.dirty)
        assertTrue(editedDraft.validation.usernameMissing)
        assertNull(editedDraft.validatedConfig())
        assertNull(draft.copy(authenticationEnabled = true, username = " \t\n").validatedConfig())
        assertNotNull(draft.copy(authenticationEnabled = true, username = "test-user").validatedConfig())
    }

    @Test
    fun disabling_authentication_omits_credentials_without_destroying_editable_draft() {
        val withCredentials = draft.copy(
            authenticationEnabled = true, username = "test-user", password = "controlled-test-value"
        )
        val withoutAuthentication = withCredentials.copy(authenticationEnabled = false)
        val config = requireNotNull(withoutAuthentication.validatedConfig())
        assertEquals("", config.username)
        assertEquals("", config.password)
        assertEquals("controlled-test-value", withoutAuthentication.password)
    }

    @Test
    fun lost_password_requires_reentry_only_while_authentication_is_enabled() {
        val restored = draft.copy(
            authenticationEnabled = true, username = "test-user", passwordNeedsReentry = true
        )
        assertTrue(restored.validation.passwordMissing)
        assertNull(restored.validatedConfig())
        assertNotNull(restored.copy(authenticationEnabled = false).validatedConfig())
        assertNotNull(restored.copy(password = "replacement-test-value", passwordNeedsReentry = false).validatedConfig())
    }

    @Test
    fun rejected_save_keeps_entire_draft_and_original_baseline() {
        val edited = initial.edit(draft.copy(portText = "8080", password = "controlled-test-value"))
        val rejected = edited.copy(saving = true).rejected()
        assertEquals(saved, rejected.saved)
        assertEquals(edited.draft, rejected.draft)
        assertTrue(rejected.dirty)
        assertTrue(rejected.saveFailed)
        assertFalse(rejected.saving)
        assertFalse(rejected.savedNotice)
        assertFalse(rejected.exitRequested)
    }

    @Test
    fun saved_emission_cannot_overwrite_a_save_in_progress() {
        val pending = initial.edit(draft.copy(portText = "8080")).copy(saving = true)
        assertEquals(pending, pending.receiveSaved(saved.copy(port = 8080)))
        assertEquals(pending, pending.edit(draft.copy(portText = "9090")))
    }

    @Test
    fun only_accepted_save_requests_exit_and_clears_dirty_state() {
        val config = saved.copy(port = 8080)
        val accepted = initial.edit(draft.copy(portText = "8080")).accepted(config, leave = true)
        assertTrue(accepted.exitRequested)
        assertTrue(accepted.savedNotice)
        assertFalse(accepted.dirty)
        assertEquals(config, accepted.saved)
    }

    @Test
    fun edits_and_resets_never_reuse_test_results_from_another_snapshot() {
        val tested = initial.copy(testedDraft = draft)
        assertTrue(tested.testMatchesDraft)
        val edited = tested.edit(draft.copy(portText = "7890x"))
        assertFalse(edited.testMatchesDraft)
        assertFalse(edited.edit(draft).testMatchesDraft)
        assertFalse(tested.receiveSaved(saved.copy(host = "localhost")).testMatchesDraft)
    }

    @Test
    fun external_saved_updates_preserve_dirty_draft() {
        val edited = initial.edit(draft.copy(portText = "8080"))
        val updated = edited.receiveSaved(saved.copy(host = "localhost"))
        assertEquals(edited.draft, updated.draft)
        assertTrue(updated.dirty)
        assertEquals("localhost", updated.saved?.host)
    }

    @Test
    fun diagnostic_strings_do_not_contain_credentials() {
        val privateDraft = draft.copy(username = "test-user", password = "controlled-test-value")
        assertFalse(privateDraft.toString().contains("controlled-test-value"))
        assertFalse(ProxyFormState(saved, privateDraft).toString().contains("controlled-test-value"))
    }

    @Test
    fun leaving_during_save_does_not_request_another_exit_when_the_page_is_reopened() {
        val saving = initial.edit(draft.copy(portText = "8080")).copy(saving = true, exitAfterSave = true)
        val left = saving.cancelAutomaticExit()
        assertTrue(left.saving)
        assertTrue(left.dirty)
        assertFalse(left.exitAfterSave)
        val accepted = left.accepted(saved.copy(port = 8080), leave = left.exitAfterSave)
        assertFalse(accepted.exitRequested)
        assertTrue(accepted.savedNotice)
    }
}
