package com.tongxie.copilotgo.ui.agent

import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolCredentialState
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.ToolSettingsSnapshot
import com.tongxie.copilotgo.data.tools.ToolSettingsState
import com.tongxie.copilotgo.data.tools.WebToolSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDisclosureTest {
    @Test fun aMissingOrFailedConfigurationNeverEnablesAutomaticReads() {
        assertFalse(hasConsentedPublicWebTools(ToolSettingsState()))
        assertFalse(hasConsentedPublicWebTools(ToolSettingsState(loading = false)))
        val ready = state(WebToolSettings(externalSharingConsent = true))
        assertTrue(hasConsentedPublicWebTools(ready))
        assertFalse(hasConsentedPublicWebTools(ready.copy(loading = true)))
        assertFalse(hasConsentedPublicWebTools(ready.copy(
            problem = ToolProblem(ToolProblemCode.STORAGE, "Controlled storage failure")
        )))
    }

    @Test fun webPermissionRequiresExplicitSharingAndAnEnabledUsableRead() {
        assertFalse(hasConsentedPublicWebTools(state(WebToolSettings())))
        assertFalse(hasConsentedPublicWebTools(state(
            WebToolSettings(searchEnabled = false, pageReaderEnabled = false, externalSharingConsent = true)
        )))
        val keyed = WebToolSettings(
            pageReaderEnabled = false, provider = SearchProvider.EXA_API_KEY, externalSharingConsent = true
        )
        assertFalse(hasConsentedPublicWebTools(state(keyed)))
        assertTrue(hasConsentedPublicWebTools(state(keyed.copy(credentialState = ToolCredentialState.CONFIGURED))))
        assertTrue(hasConsentedPublicWebTools(state(keyed.copy(pageReaderEnabled = true))))
    }

    private fun state(web: WebToolSettings) = ToolSettingsState(false, ToolSettingsSnapshot(web))
}
