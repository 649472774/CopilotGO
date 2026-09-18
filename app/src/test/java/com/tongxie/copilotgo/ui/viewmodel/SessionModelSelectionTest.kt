package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.chat.CoreFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionModelSelectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun newConversationNeverChoosesADisabledAdvertisedDefault() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            CoreFixture(temporary.root).use { core ->
                core.models = {
                    MockResponse().setBody(
                        """{"data":[{"id":"blocked","is_chat_default":true,"policy":{"state":"disabled"}},{"id":"available","supported_endpoints":["/responses"]}]}"""
                    )
                }
                core.catalog.refresh(force = true)
                val owner = ViewModelStore()
                val viewModel = SessionListViewModel(core.store, core.client, core.auth, core.center)
                val job = viewModel.viewModelScope.coroutineContext[Job]
                owner.put("sessions", viewModel)
                try {
                    assertEquals("available", viewModel.createNew().model)
                } finally {
                    owner.clear()
                    job?.join()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
