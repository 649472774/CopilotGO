package com.tongxie.copilotgo.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCompletionsTest {
    @Test
    fun file_callback_completes_once() {
        val pending = PendingWebCallback<String>()
        val values = mutableListOf<String?>()
        val id = pending.begin(values::add)!!
        pending.complete(id, "selected")
        pending.complete(id, "duplicate")
        pending.cancel()
        assertEquals(listOf("selected"), values)
        assertFalse(pending.isPending)
    }

    @Test
    fun concurrent_picker_is_cancelled_without_replacing_the_first_request() {
        val pending = PendingWebCallback<String>()
        val first = mutableListOf<String?>()
        val second = mutableListOf<String?>()
        val id = pending.begin(first::add)!!
        assertNull(pending.begin(second::add))
        assertEquals(listOf<String?>(null), second)
        assertTrue(pending.matches(id))
        pending.complete(id, "first")
        assertEquals(listOf("first"), first)
    }

    @Test
    fun stale_picker_result_cannot_complete_a_new_request() {
        val pending = PendingWebCallback<String>()
        val values = mutableListOf<String?>()
        val old = pending.begin(values::add)!!
        pending.cancel()
        val current = pending.begin(values::add)!!
        assertFalse(pending.complete(old, "old result"))
        assertTrue(pending.matches(current))
        pending.complete(current, "new result")
        assertEquals(listOf(null, "new result"), values)
    }

    @Test
    fun detach_and_renderer_loss_cancel_exactly_once() {
        val pending = PendingWebCallback<String>()
        val values = mutableListOf<String?>()
        pending.begin(values::add)
        pending.cancel()
        pending.cancel()
        assertEquals(listOf<String?>(null), values)
    }

    @Test
    fun cookie_removal_is_awaited_before_follow_up_work() {
        val barrier = CookieLogoutBarrier()
        val events = mutableListOf<String>()
        var completion: ((Boolean) -> Unit)? = null
        barrier.begin(
            stopPage = { events += "stop" },
            removeCookies = { completion = it; events += "remove" },
            afterRemoval = { events += "reload" }
        )
        assertEquals(listOf("stop", "remove"), events)
        completion!!.invoke(true)
        assertEquals(listOf("stop", "remove", "reload"), events)
        completion!!.invoke(true)
        assertEquals(3, events.size)
    }

    @Test
    fun no_existing_cookies_is_a_completed_removal_not_a_failure() {
        val barrier = CookieLogoutBarrier()
        var complete = false
        barrier.begin({}, { it(false) }, { complete = true })
        assertTrue(complete)
    }

    @Test
    fun overlapping_cookie_clear_is_not_started() {
        val barrier = CookieLogoutBarrier()
        assertTrue(barrier.begin({}, {}, {}))
        assertFalse(barrier.begin({ error("must not run") }, {}, {}))
    }

    @Test
    fun invalidated_cookie_callback_cannot_reload() {
        val barrier = CookieLogoutBarrier()
        var completion: ((Boolean) -> Unit)? = null
        var reloaded = false
        barrier.begin({}, { completion = it }, { reloaded = true })
        barrier.invalidate()
        completion!!.invoke(true)
        assertFalse(reloaded)
    }
}
