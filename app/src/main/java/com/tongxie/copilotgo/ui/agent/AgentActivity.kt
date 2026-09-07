package com.tongxie.copilotgo.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.AgentApprovalBinding
import com.tongxie.copilotgo.data.agent.AgentApprovalDecision
import com.tongxie.copilotgo.data.agent.AgentApprovalRequest
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentToolCallRecord
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val reviewJson = Json { prettyPrint = true }
private data class PreparedAgentArguments(val arguments: JsonObject, val preview: AgentTextPreview)

@Composable
internal fun AgentRunIndicator(
    run: AgentRunRecord,
    onReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onReview,
        modifier = modifier.sizeIn(minHeight = 48.dp).testTag(AgentTags.ACTIVITY)
    ) {
        Text(
            stringResource(R.string.agent_activity_action, stringResource(agentRunLabel(run))),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
internal fun AgentToolbarActivity(run: AgentRunRecord, onReview: () -> Unit) {
    val status = stringResource(agentRunLabel(run))
    TextButton(
        onClick = onReview,
        modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { stateDescription = status }
            .testTag(AgentTags.TOOLBAR_ACTIVITY)
    ) {
        Text(
            stringResource(
                if (run.status == AgentRunStatus.AWAITING_APPROVAL) R.string.agent_review_pending_short
                else R.string.agent_tools_short
            ),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
internal fun AgentMessageActivity(run: AgentRunRecord, onReview: (() -> Unit)?) {
    val calls = remember(run.steps) { displayedAgentCalls(run) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onReview != null) AgentRunIndicator(run, onReview)
        else Text(stringResource(agentRunLabel(run)), style = MaterialTheme.typography.titleMedium)
        calls.takeLast(3).forEach { call ->
            Column(
                Modifier.fillMaxWidth()
                    .then(if (onReview != null) Modifier.clickable(role = Role.Button, onClick = onReview) else Modifier)
                    .sizeIn(minHeight = 48.dp).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    agentTextPreview(call.name, 160).text,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(stringResource(agentCallLabel(call)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (run.status == AgentRunStatus.INTERRUPTED) {
            Text(stringResource(R.string.agent_interrupted_detail), style = MaterialTheme.typography.bodyMedium)
        }
        if (calls.any { it.outcomeUnknown || it.result?.outcomeUnknown == true }) {
            Text(
                stringResource(R.string.agent_unknown_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
internal fun AgentRunDetailsDialog(
    run: AgentRunRecord,
    reviewedApproval: AgentApprovalRequest?,
    approvalBusy: Boolean,
    approvalError: String?,
    onReviewApproval: (AgentApprovalRequest) -> Unit,
    onDecision: (AgentApprovalBinding, AgentApprovalDecision) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenSource: (String) -> Unit,
    approvalsAvailable: Boolean = true
) {
    val title = stringResource(R.string.agent_activity_title)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            Modifier.fillMaxSize().semantics { paneTitle = title },
            color = MaterialTheme.colorScheme.surface
        ) {
            AgentRunDetailsContent(
                run, reviewedApproval, approvalBusy, approvalError,
                onReviewApproval, onDecision, onStop, onClose, onOpenSource, approvalsAvailable
            )
        }
    }
}

@Composable
internal fun AgentRunDetailsContent(
    run: AgentRunRecord,
    reviewedApproval: AgentApprovalRequest?,
    approvalBusy: Boolean,
    approvalError: String?,
    onReviewApproval: (AgentApprovalRequest) -> Unit,
    onDecision: (AgentApprovalBinding, AgentApprovalDecision) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    approvalsAvailable: Boolean = true
) {
    val calls = remember(run.steps) { displayedAgentCalls(run) }
    val listState = rememberLazyListState()
    LaunchedEffect(reviewedApproval?.binding) {
        // This snapshot changes only when the user opens a review, not when a new proposal arrives.
        if (reviewedApproval != null) listState.requestScrollToItem(1)
    }
    PageScaffold(
        stringResource(R.string.agent_activity_title),
        onBack,
        actions = {
            if (!run.status.isTerminal) {
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.sizeIn(minHeight = 48.dp).testTag(AgentTags.STOP)
                ) { Text(stringResource(R.string.composer_stop)) }
            }
        }
    ) { pageModifier ->
        LazyColumn(
            pageModifier.testTag(AgentTags.DETAILS),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(agentRunLabel(run)),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }
                    )
                    Text(stringResource(R.string.agent_activity_not_reasoning), style = MaterialTheme.typography.bodyMedium)
                    if (!run.status.isTerminal) {
                        Text(stringResource(R.string.agent_leave_running), style = MaterialTheme.typography.bodyMedium)
                    }
                    run.notice?.let { AgentPlainOutput(it) }
                    if (run.status.isTerminal && !run.safeToRetry) {
                        FeedbackBanner(stringResource(R.string.agent_no_replay), isError = true)
                    }
                }
            }
            if (reviewedApproval != null) {
                item(key = "review-${reviewedApproval.binding.approvalId}") {
                    AgentApprovalCard(
                        request = reviewedApproval,
                        run = run,
                        busy = approvalBusy,
                        error = approvalError,
                        onDecision = onDecision,
                        available = approvalsAvailable
                    )
                }
            }
            val pending = run.pendingApproval
            if (pending != null && pending != reviewedApproval) {
                item(key = "pending-${pending.binding.approvalId}") {
                    OutlinedButton(
                        onClick = { onReviewApproval(pending) },
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(AgentTags.REVIEW)
                    ) { Text(stringResource(R.string.agent_review_current)) }
                }
            }
            items(calls, key = { "${run.id}-${it.id}" }) { call ->
                Column(Modifier.fillMaxWidth().testTag("agent-call-record-${call.id}")) {
                    AgentCallDetails(call)
                    HorizontalDivider()
                }
            }
            if (run.sources.isNotEmpty()) {
                item(key = "sources-heading") {
                    Text(
                        stringResource(R.string.agent_sources_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }.testTag(AgentTags.SOURCES)
                    )
                }
                items(
                    run.sources.take(MAX_DISPLAYED_AGENT_SOURCES),
                    key = { "${it.id}-${it.toolCallId}-${it.url}" }
                ) { source -> AgentSourceRow(source, onOpenSource) }
                if (run.sources.size > MAX_DISPLAYED_AGENT_SOURCES) {
                    item(key = "source-limit") {
                        Text(stringResource(R.string.agent_sources_preview_limit), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentApprovalCard(
    request: AgentApprovalRequest,
    run: AgentRunRecord,
    busy: Boolean,
    error: String?,
    onDecision: (AgentApprovalBinding, AgentApprovalDecision) -> Unit,
    available: Boolean = true
) {
    var expired by remember(request.binding, request.expiresAt) {
        mutableStateOf(System.currentTimeMillis() >= request.expiresAt)
    }
    LaunchedEffect(request.binding, request.expiresAt) {
        delay((request.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
        expired = true
    }
    val current = approvalMatchesRun(request, run)
    val canAnswer = current && !expired && approvalCanBeAnswered(request, run, System.currentTimeMillis())
    var argumentsReviewable by remember(request) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().testTag("agent-approval-${request.binding.approvalId}"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.agent_approval_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(agentTextPreview(request.toolName, 256).text, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.agent_destination), style = MaterialTheme.typography.labelLarge)
        Text(agentTextPreview(request.destination, 2_048).text, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.agent_approval_scope), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(
                R.string.agent_approval_identity,
                agentTextPreview(request.binding.tool.configId, 128).text,
                request.binding.tool.configRevision,
                agentTextPreview(request.binding.callId, 128).text
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        AgentArguments(request.arguments, onReviewable = { argumentsReviewable = it })
        Text(
            stringResource(R.string.agent_arguments_digest, agentTextPreview(request.binding.argumentsDigest, 128).text),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        when {
            !current -> FeedbackBanner(stringResource(R.string.agent_approval_stale), isError = true)
            expired -> FeedbackBanner(stringResource(R.string.agent_approval_expired), isError = true)
        }
        error?.let { FeedbackBanner(agentTextPreview(it, 2_048).text, isError = true) }
        if (!available) FeedbackBanner(stringResource(R.string.agent_approval_unavailable), isError = true)
        Button(
            onClick = { onDecision(request.binding, AgentApprovalDecision.APPROVE) },
            enabled = available && canAnswer && argumentsReviewable && !busy,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(AgentTags.APPROVE)
        ) {
            Text(stringResource(if (busy) R.string.agent_answering_approval else R.string.agent_approve_once))
        }
        OutlinedButton(
            onClick = { onDecision(request.binding, AgentApprovalDecision.DENY) },
            enabled = available && canAnswer && !busy,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(AgentTags.DENY)
        ) { Text(stringResource(R.string.agent_deny_once)) }
    }
}

@Composable
private fun AgentCallDetails(call: AgentToolCallRecord) {
    var expanded by remember(call.id) { mutableStateOf(false) }
    val destination = remember(call.destination, expanded) {
        agentTextPreview(call.destination, if (expanded) 2_048 else 256)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            agentTextPreview(call.name, 256).text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            stringResource(agentCallLabel(call)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("agent-call-status-${call.id}")
        )
        if (call.destination.isNotBlank()) {
            Text(destination.text, style = MaterialTheme.typography.bodyMedium)
            if (destination.truncated) {
                Text(stringResource(R.string.agent_destination_preview), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (call.arguments != null || call.result != null || destination.truncated) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .testTag("agent-call-details-${call.id}")
            ) {
                Text(stringResource(if (expanded) R.string.agent_call_collapse else R.string.agent_call_expand))
            }
        }
        if (expanded) {
            call.arguments?.let { AgentArguments(it) }
            call.result?.let { result ->
                Text(
                    stringResource(if (result.isError) R.string.agent_tool_error else R.string.agent_tool_result),
                    style = MaterialTheme.typography.labelLarge
                )
                AgentPlainOutput(result.content)
                if (result.truncated) {
                    Text(stringResource(R.string.agent_result_limited), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (call.outcomeUnknown || call.result?.outcomeUnknown == true) {
            FeedbackBanner(stringResource(R.string.agent_unknown_detail), isError = true)
        }
    }
}

@Composable
private fun AgentArguments(arguments: JsonObject, onReviewable: (Boolean) -> Unit = {}) {
    val prepared by produceState<PreparedAgentArguments?>(initialValue = null, arguments) {
        value = withContext(Dispatchers.Default) {
            PreparedAgentArguments(
                arguments,
                agentTextPreview(
                    reviewJson.encodeToString(JsonObject.serializer(), arguments),
                    MAX_REVIEW_ARGUMENT_CHARACTERS
                )
            )
        }
    }
    val preview = prepared?.takeIf { it.arguments == arguments }?.preview
    LaunchedEffect(arguments, preview) { onReviewable(preview?.truncated == false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.agent_arguments), style = MaterialTheme.typography.labelLarge)
        val rendered = preview
        if (rendered == null) {
            Text(stringResource(R.string.agent_arguments_loading), style = MaterialTheme.typography.bodyMedium)
        } else {
            SelectionContainer {
                Text(
                    rendered.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()).testTag(AgentTags.ARGUMENTS)
                )
            }
            if (rendered.truncated) {
                FeedbackBanner(stringResource(R.string.agent_arguments_unreviewable), isError = true)
            }
        }
    }
}

@Composable
private fun AgentPlainOutput(text: String) {
    val preview = remember(text) { agentTextPreview(text, 8_192) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Text(
                preview.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
            )
        }
        if (preview.truncated) {
            Text(stringResource(R.string.agent_output_preview), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
