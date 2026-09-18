package com.tongxie.copilotgo.ui.agent

import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.chat.AttachmentKind
import com.tongxie.copilotgo.data.chat.AttachmentRef
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.ToolSettingsSnapshot
import com.tongxie.copilotgo.data.tools.ToolSettingsState
import com.tongxie.copilotgo.data.tools.WebToolSettings
import com.tongxie.copilotgo.ui.draft.ComposerDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticSearchConsentTest {
    @Test fun externalSharingAloneDoesNotAuthorizeAutomaticSearch() {
        assertFalse(hasAutomaticSearchConsent(state(WebToolSettings())))
        assertFalse(hasAutomaticSearchConsent(state(WebToolSettings(externalSharingConsent = true))))
        assertFalse(hasAutomaticSearchConsent(state(readyWeb().copy(externalSharingConsent = false))))
        assertFalse(hasAutomaticSearchConsent(state(readyWeb().copy(searchEnabled = false))))
        assertTrue(hasAutomaticSearchConsent(state(readyWeb())))
        assertTrue(hasAutomaticSearchConsent(state(readyWeb().copy(pageReaderEnabled = false))))
    }

    @Test fun unreadableOrLoadingSettingsNeverUseTheLastSnapshotOrFreshDefaults() {
        assertNull(readableAutomaticSearchSettings(ToolSettingsState()))
        assertNull(readableAutomaticSearchSettings(ToolSettingsState(loading = false)))
        val loading = state(readyWeb()).copy(loading = true)
        val failed = state(readyWeb()).copy(problem = ToolProblem(ToolProblemCode.STORAGE, "controlled"))
        assertFalse(hasAutomaticSearchConsent(loading))
        assertFalse(hasAutomaticSearchConsent(failed))
        assertNull(readableAutomaticSearchSettings(loading))
        assertNull(readableAutomaticSearchSettings(failed))
    }

    @Test fun submissionBindsTheOriginalSessionSettingsAndCompleteDraftSnapshot() {
        val submission = submission()
        assertTrue(submission.matches("session", submission.draft.copy(), submission.agentSettings.copy()))
        assertFalse(submission.matches("other-session", submission.draft, submission.agentSettings))
        assertFalse(submission.matches(null, submission.draft, null))
        assertFalse(submission.matches("session", submission.draft, submission.agentSettings.copy(enabled = true)))
        assertFalse(submission.matches("session", submission.draft, submission.agentSettings.copy(automaticWebSearch = false)))
        assertFalse(submission.matches("session", submission.draft, submission.agentSettings.copy(autoApprovePublicWebReads = true)))
        assertFalse(submission.matches("session", submission.draft, submission.agentSettings.copy(
            limits = submission.agentSettings.limits.copy(maxSteps = 3)
        )))
    }

    @Test fun changedTextAttachmentsRevisionOrSubmissionIdCannotRetargetConsent() {
        val submission = submission()
        val original = submission.draft
        val changedDrafts = listOf(
            original.copy(text = "新问题"),
            original.copy(attachments = emptyList()),
            original.copy(revision = original.revision + 1),
            original.copy(submissionId = "another-submission")
        )
        changedDrafts.forEach {
            assertFalse(submission.matches("session", it, submission.agentSettings))
        }
    }

    private fun readyWeb() = WebToolSettings(externalSharingConsent = true, automaticSearchConsent = true)
    private fun state(web: WebToolSettings) = ToolSettingsState(false, ToolSettingsSnapshot(web))
    private fun submission() = AutomaticSearchSubmission(
        "session",
        ComposerDraft(
            text = "北京今天的天气",
            attachments = listOf(AttachmentRef("fixture", "附件.txt", "text/plain", 8, AttachmentKind.TEXT)),
            revision = 7,
            submissionId = "original-submission"
        ),
        AgentSessionSettings()
    )
}
