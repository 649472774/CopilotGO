package com.tongxie.copilotgo.ui.settings

import com.tongxie.copilotgo.ui.viewmodel.AccountActionsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AccountActionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun logout_waits_for_durable_completion_and_ignores_duplicate_submission() = runTest(dispatcher) {
        val vm = AccountActionsViewModel()
        val complete = CompletableDeferred<Unit>()
        var calls = 0
        vm.confirm()
        vm.logout { calls++; complete.await() }
        vm.logout { calls++ }
        vm.dismiss()
        runCurrent()
        assertEquals(1, calls)
        assertEquals(AccountActionsViewModel.State.Working, vm.state.value)
        complete.complete(Unit)
        runCurrent()
        assertEquals(AccountActionsViewModel.State.Completed, vm.state.value)
    }

    @Test
    fun a_rejected_clear_retains_retry_state_and_never_reports_completion() = runTest(dispatcher) {
        val vm = AccountActionsViewModel()
        vm.confirm()
        vm.logout { throw IOException("controlled test failure") }
        runCurrent()
        assertEquals(AccountActionsViewModel.State.Failed, vm.state.value)
        vm.logout { }
        runCurrent()
        assertEquals(AccountActionsViewModel.State.Completed, vm.state.value)
    }

    @Test
    fun logout_requires_explicit_confirmation() = runTest(dispatcher) {
        val vm = AccountActionsViewModel()
        var calls = 0
        vm.logout { calls++ }
        runCurrent()
        assertEquals(0, calls)
        assertEquals(AccountActionsViewModel.State.Idle, vm.state.value)
    }

    @Test
    fun dismissing_a_failed_confirmation_retains_the_clear_error_for_the_account_page() = runTest(dispatcher) {
        val vm = AccountActionsViewModel()
        vm.confirm()
        vm.logout { throw IOException("controlled test failure") }
        runCurrent()
        vm.dismiss()
        assertEquals(AccountActionsViewModel.State.FailureDismissed, vm.state.value)
        vm.confirm()
        assertEquals(AccountActionsViewModel.State.Failed, vm.state.value)
    }
}
