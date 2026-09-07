package com.tongxie.copilotgo.ui.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteProxyControllerTest {
    private val route = RemoteProxyRoute.Override("http://proxy.example:7890")

    @Test
    fun loads_are_not_enabled_until_native_callback() = runTest {
        val backend = Backend()
        val states = mutableListOf<RemoteProxyStatus>()
        val controller = RemoteProxyController(this, backend, states::add)
        controller.request(route)
        assertEquals(listOf(RemoteProxyStatus.Applying), states)
        backend.finish(true)
        assertEquals(RemoteProxyStatus.Ready(route), states.last())
    }

    @Test
    fun clear_waits_for_in_flight_set_and_only_reports_final_system_route() = runTest {
        val backend = Backend()
        val states = mutableListOf<RemoteProxyStatus>()
        val controller = RemoteProxyController(this, backend, states::add)
        controller.request(route)
        controller.request(RemoteProxyRoute.System)
        assertEquals(listOf(route), backend.routes)
        backend.finish(true)
        assertEquals(listOf(route, RemoteProxyRoute.System), backend.routes)
        assertTrue(states.none { it is RemoteProxyStatus.Ready })
        backend.finish(true)
        assertEquals(RemoteProxyStatus.Ready(RemoteProxyRoute.System), states.last())
    }

    @Test
    fun rapid_configuration_changes_apply_latest_route_without_intermediate_load() = runTest {
        val backend = Backend()
        val states = mutableListOf<RemoteProxyStatus>()
        val controller = RemoteProxyController(this, backend, states::add)
        val skipped = RemoteProxyRoute.Override("http://skipped.example:8080")
        val latest = RemoteProxyRoute.Override("socks://latest.example:1080")
        controller.request(route)
        controller.request(skipped)
        controller.request(latest)
        backend.finish(true)
        backend.finish(true)
        assertEquals(listOf(route, latest), backend.routes)
        assertEquals(listOf(RemoteProxyStatus.Ready(latest)), states.filterIsInstance<RemoteProxyStatus.Ready>())
    }

    @Test
    fun timeout_blocks_and_does_not_allow_late_callback_to_undo_a_newer_clear() = runTest {
        val backend = Backend()
        val states = mutableListOf<RemoteProxyStatus>()
        val controller = RemoteProxyController(this, backend, states::add)
        controller.request(route)
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(RemoteProxyStatus.Failed(timedOut = true), states.last())
        controller.request(RemoteProxyRoute.System)
        assertEquals(1, backend.routes.size)
        backend.finish(true)
        backend.finish(true)
        assertEquals(RemoteProxyStatus.Ready(RemoteProxyRoute.System), states.last())
    }

    @Test
    fun failure_is_not_a_system_network_success_and_can_be_retried() = runTest {
        val backend = Backend()
        val states = mutableListOf<RemoteProxyStatus>()
        val controller = RemoteProxyController(this, backend, states::add)
        controller.request(route)
        backend.finish(false)
        assertEquals(RemoteProxyStatus.Failed(timedOut = false), states.last())
        controller.request(route)
        assertEquals(listOf(route, route), backend.routes)
        backend.finish(true)
        assertEquals(RemoteProxyStatus.Ready(route), states.last())
    }

    @Test
    fun reentering_unchanged_route_does_not_reconfigure_native_proxy() = runTest {
        val backend = Backend()
        val controller = RemoteProxyController(this, backend) {}
        controller.request(route)
        backend.finish(true)
        controller.request(route)
        assertEquals(1, backend.routes.size)
    }

    private class Backend : RemoteProxyBackend {
        override val supported = true
        val routes = mutableListOf<RemoteProxyRoute>()
        private val callbacks = ArrayDeque<(Boolean) -> Unit>()

        override fun configure(route: RemoteProxyRoute, completion: (Boolean) -> Unit) {
            routes += route
            callbacks.addLast(completion)
        }

        fun finish(success: Boolean) = callbacks.removeFirst().invoke(success)
    }
}
