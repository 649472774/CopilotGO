package com.tongxie.copilotgo.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBrowserStateTest {
    private val url = "https://github.com/copilot"
    private val problem = RemoteProblem("failure", "retry")

    @Test
    fun late_finished_and_progress_callbacks_do_not_erase_errors() {
        val failed = RemotePageState().started(url).failed(problem)
        assertEquals(failed, failed.finished(url).progressed(100))
    }

    @Test
    fun stopping_load_cannot_be_reported_as_success() {
        val stopped = RemotePageState().started(url).interrupted()
        assertEquals(RemoteLoadPhase.INTERRUPTED, stopped.finished(url).phase)
        assertFalse(stopped.finished(url).hasVisiblePage)
    }

    @Test
    fun earlier_navigation_finish_does_not_finish_a_redirect() {
        val login = "https://github.com/login"
        val redirected = RemotePageState().started(url).started(login)
        assertEquals(RemoteLoadPhase.LOADING, redirected.finished(url).phase)
        assertEquals(RemoteLoadPhase.READY, redirected.committed(login).finished(login).phase)
    }

    @Test
    fun renderer_loss_has_explicit_recovery_state_without_fake_page_or_history() {
        val ready = RemotePageState().started(url).committed(url).finished(url).copy(canGoBack = true)
        val lost = ready.released(problem)
        assertEquals(RemoteLoadPhase.RELEASED, lost.phase)
        assertFalse(lost.hasVisiblePage)
        assertFalse(lost.canGoBack)
        assertEquals(url, lost.committedUrl)
    }

    @Test
    fun page_updates_preserve_actual_desktop_and_immersive_preferences() {
        val preferences = RemotePreferences(desktop = true, immersive = false, RemoteNetworkMode.APP_PROXY)
        val retained = RemoteBrowserState(preferences = preferences, preferencesReady = true)
        val updated = retained.copy(page = retained.page.started(url).failed(problem))
        assertEquals(preferences, updated.preferences)
        assertTrue(updated.preferencesReady)
    }

    @Test
    fun retry_explicitly_resets_error_and_loading_progress() {
        val retried = RemotePageState().started(url).failed(problem).started(url)
        assertEquals(RemoteLoadPhase.LOADING, retried.phase)
        assertEquals(0, retried.progress)
        assertEquals(null, retried.problem)
    }

    @Test
    fun finished_without_current_visual_commit_never_exposes_a_blank_page_as_ready() {
        val finished = RemotePageState().started(url).finished(url)
        assertEquals(RemoteLoadPhase.LOADING, finished.phase)
        assertFalse(finished.hasVisiblePage)
        assertFalse(finished.committedForLoad)
    }

    @Test
    fun engine_finish_before_visual_commit_completes_only_when_content_commits() {
        val finished = RemotePageState().started(url).finished(url).committed(url)
        assertEquals(RemoteLoadPhase.READY, finished.phase)
        assertTrue(finished.hasVisiblePage)
    }

    @Test
    fun http_failure_before_commit_time_page_start_is_not_erased() {
        val failed = RemotePageState().started(url).committed(url).finished(url)
            .failedFor(url, problem)
        assertEquals(failed, failed.frameStarted(url).committed(url).finished(url))
        assertEquals(RemoteLoadPhase.LOADING, failed.started(url).phase)
    }
    @Test
    fun ime_uses_overlap_not_an_extra_full_keyboard_height() {
        assertEquals(276, remoteImeOverlap(rootHeight = 1000, hostBottom = 976, imeBottom = 300))
        assertEquals(300, remoteImeOverlap(rootHeight = 1000, hostBottom = 1000, imeBottom = 300))
        assertEquals(0, remoteImeOverlap(rootHeight = 1000, hostBottom = 650, imeBottom = 300))
        assertEquals(0, remoteImeOverlap(rootHeight = 1000, hostBottom = 1100, imeBottom = 0))
        assertEquals(0, remoteImeOverlap(rootHeight = 0, hostBottom = 0, imeBottom = 300))
    }

    @Test
    fun desktop_user_agent_keeps_the_real_engine_version() {
        val mobile = "Mozilla/5.0 (Linux; Android 16; Pixel Build/BP2; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36"
        val desktop = desktopUserAgent(mobile)
        assertTrue(desktop.contains("(X11; Linux x86_64)"))
        assertTrue(desktop.contains("Chrome/140.0.0.0"))
        assertFalse(desktop.contains("Android"))
        assertFalse(desktop.contains(" Mobile "))
        assertFalse(desktop.contains("Version/4.0"))
    }
}
