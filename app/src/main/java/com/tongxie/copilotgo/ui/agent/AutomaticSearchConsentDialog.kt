package com.tongxie.copilotgo.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.chat.OperationResult
import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolSettingsState
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.theme.AppLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal object AutomaticSearchTags {
    const val DIALOG = "automatic-search-consent"
    const val CONTEXT = "automatic-search-request"
    const val CONFIRM = "automatic-search-confirm"
    const val CANCEL = "automatic-search-cancel"
    const val ERROR = "automatic-search-error"
    const val RECOVER = "automatic-search-recover"
}

@Composable
internal fun AutomaticSearchConsentDialog(
    submission: AutomaticSearchSubmission,
    toolSettings: ToolSettingsState,
    isSubmissionCurrent: (AutomaticSearchSubmission) -> Boolean,
    onAuthorize: suspend (Long) -> OperationResult,
    onAuthorized: (AutomaticSearchSubmission) -> Unit,
    onOpenTools: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.automatic_search_consent_title)
    val latestMatches by rememberUpdatedState(isSubmissionCurrent)
    val latestAuthorized by rememberUpdatedState(onAuthorized)
    val latestDismiss by rememberUpdatedState(onDismiss)
    var reviewedWeb by remember(submission) { mutableStateOf(readableAutomaticSearchSettings(toolSettings)) }
    var active by remember(submission) { mutableStateOf(true) }
    var saving by remember(submission) { mutableStateOf(false) }
    var error by remember(submission) { mutableStateOf<String?>(null) }
    val currentWeb = readableAutomaticSearchSettings(toolSettings)
    val staleSubmission = !isSubmissionCurrent(submission)
    val staleConfiguration = reviewedWeb != null && currentWeb != null && reviewedWeb != currentWeb
    val canConfirm = active && !saving && !staleSubmission && !staleConfiguration &&
        reviewedWeb != null && currentWeb != null
    val requestPreview = remember(submission) { agentTextPreview(submission.draft.text.trim(), 1_024) }

    LaunchedEffect(currentWeb) {
        if (reviewedWeb == null && currentWeb != null) reviewedWeb = currentWeb
    }
    DisposableEffect(submission) {
        onDispose { active = false }
    }

    fun dismiss() {
        active = false
        latestDismiss()
    }

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().semantics { paneTitle = title }.testTag(AutomaticSearchTags.DIALOG),
            color = MaterialTheme.colorScheme.surface
        ) {
            PageScaffold(title, ::dismiss) { pageModifier ->
                Column(
                    modifier = pageModifier.verticalScroll(rememberScrollState()).padding(AppLayout.PageGutter),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.automatic_search_consent_detail), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.automatic_search_query_scope), style = MaterialTheme.typography.bodyMedium)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(stringResource(R.string.automatic_search_request_context), style = MaterialTheme.typography.titleSmall)
                            Text(
                                requestPreview.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag(AutomaticSearchTags.CONTEXT)
                            )
                            if (requestPreview.truncated) {
                                Text(stringResource(R.string.automatic_search_request_truncated), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    reviewedWeb?.let { web ->
                        Text(
                            stringResource(
                                if (web.provider == SearchProvider.EXA_KEYLESS) R.string.agent_provider_keyless
                                else R.string.agent_provider_own_key
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            stringResource(
                                if (web.pageReaderEnabled) R.string.automatic_search_pages_enabled
                                else R.string.automatic_search_pages_disabled
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(stringResource(R.string.automatic_search_privacy), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.automatic_search_mcp_not_authorized), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.automatic_search_limits_and_revoke), style = MaterialTheme.typography.bodyMedium)
                    when {
                        staleSubmission -> FeedbackBanner(
                            stringResource(R.string.automatic_search_stale_submission),
                            isError = true,
                            modifier = Modifier.testTag(AutomaticSearchTags.ERROR)
                        )
                        toolSettings.loading -> FeedbackBanner(stringResource(R.string.automatic_search_settings_loading))
                        currentWeb == null -> {
                            FeedbackBanner(
                                stringResource(R.string.automatic_search_settings_failed),
                                isError = true,
                                modifier = Modifier.testTag(AutomaticSearchTags.ERROR)
                            )
                            toolSettings.problem?.let {
                                Text(agentTextPreview(it.message, 2_048).text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        staleConfiguration -> FeedbackBanner(
                            stringResource(R.string.automatic_search_stale_configuration),
                            isError = true,
                            modifier = Modifier.testTag(AutomaticSearchTags.ERROR)
                        )
                        error != null -> FeedbackBanner(
                            agentTextPreview(requireNotNull(error), 2_048).text,
                            isError = true,
                            modifier = Modifier.testTag(AutomaticSearchTags.ERROR)
                        )
                    }
                    if (saving) FeedbackBanner(stringResource(R.string.automatic_search_saving))
                    OutlinedButton(
                        onClick = ::dismiss,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(AutomaticSearchTags.CANCEL)
                    ) { Text(stringResource(R.string.automatic_search_cancel)) }
                    Button(
                        enabled = canConfirm,
                        onClick = {
                            if (canConfirm && !saving && active && latestMatches(submission)) {
                                val revision = requireNotNull(reviewedWeb).revision
                                saving = true
                                error = null
                                scope.launch {
                                    try {
                                        when (val result = onAuthorize(revision)) {
                                            OperationResult.Accepted -> {
                                                ensureActive()
                                                if (active) {
                                                    if (latestMatches(submission)) {
                                                        active = false
                                                        latestAuthorized(submission)
                                                        latestDismiss()
                                                    } else {
                                                        error = context.getString(R.string.automatic_search_stale_submission)
                                                    }
                                                }
                                            }
                                            is OperationResult.Rejected -> if (active) error = result.message
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        if (active) error = context.getString(R.string.automatic_search_authorize_failed)
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(AutomaticSearchTags.CONFIRM)
                    ) {
                        Text(stringResource(if (saving) R.string.state_saving else R.string.automatic_search_confirm))
                    }
                    if (currentWeb == null || staleConfiguration || error != null) {
                        TextButton(
                            onClick = { dismiss(); onOpenTools() },
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(AutomaticSearchTags.RECOVER)
                        ) { Text(stringResource(R.string.automatic_search_settings_recover)) }
                    }
                }
            }
        }
    }
}
