package com.tongxie.copilotgo.ui

import android.view.WindowManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.storage.SecretVault
import com.tongxie.copilotgo.data.tools.McpServerDraft
import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolCredentialState
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.ToolSettingsStore
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveredTool
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveryReport
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveryState
import com.tongxie.copilotgo.data.tools.mcp.RemoteMcpService
import com.tongxie.copilotgo.data.tools.net.ToolHttpClient
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.screens.McpServerSettingsScreen
import com.tongxie.copilotgo.ui.screens.SearchToolSettingsScreen
import com.tongxie.copilotgo.ui.screens.ToolSettingsScreen
import com.tongxie.copilotgo.ui.settings.ToolMcpForm
import com.tongxie.copilotgo.ui.settings.mcpSettingsDiscoveryItems
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme
import com.tongxie.copilotgo.ui.viewmodel.ToolSettingsViewModel
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real settings stores, VMs and discovery services; transport readiness never permits network IO. */
@RunWith(AndroidJUnit4::class)
class AgentSettingsAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val fixtures = mutableListOf<Fixture>()

    @Before fun prepareWindow() {
        rule.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @After fun releaseFixtures() {
        rule.runOnIdle { fixtures.forEach { it.owner.clear() } }
        fixtures.forEach {
            it.scope.cancel()
            it.provider.client.connectionPool.evictAll()
            it.provider.client.dispatcher.executorService.shutdown()
            assertEquals("The isolated fixture must never resolve a real host", 0, it.provider.dnsCalls.get())
        }
    }

    @Test fun addingEditingAndDeletingUseTheRealStoreAndExplicitConfirmation() {
        val fixture = fixture()
        rule.setContent { FixtureTheme { SettingsRoutes(fixture) } }
        scroll("tool-settings-list", "tool-add-server").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil { fixture.editor.state.value.server != null }
        replace("tool-server-label", "受控中文 MCP 服务")
        replace("tool-server-endpoint", ENDPOINT)
        Espresso.closeSoftKeyboard()
        scroll(MCP_LIST, "tool-settings-save").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(10_000) { fixture.store.state.value.snapshot?.servers?.size == 1 && fixture.editor.state.value.pending == null }
        val server = fixture.store.state.value.snapshot!!.servers.single()
        assertTrue(server.id.matches(Regex("[0-9a-f]{16}")))
        rule.runOnIdle {
            fixture.editor.openServer(null)
            assertEquals("Re-entering a recreated add route must not reset its saved server", server.id, fixture.editor.state.value.server?.serverId)
        }
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        scroll("tool-settings-list", "tool-edit-server-${server.id}").performClick()
        scroll(MCP_LIST, "tool-delete-server").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText(text(R.string.tool_settings_cancel)).performScrollTo().performClick()
        assertEquals(1, fixture.store.state.value.snapshot?.servers?.size)
        scroll(MCP_LIST, "tool-delete-server").performClick()
        rule.onNodeWithText(text(R.string.tool_settings_delete_confirm)).performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        screenshot("tool-delete-confirm-200", dialog = true)
        rule.onNodeWithText(text(R.string.tool_settings_delete_confirm)).performClick()
        rule.waitUntil(10_000) { fixture.store.state.value.snapshot?.servers?.isEmpty() == true && fixture.route.value == Route.LIST }
        scroll("tool-settings-list", "tool-add-server").assertIsEnabled()
        assertEquals(0, fixture.provider.readinessCalls.get())
    }

    @Test fun invalidEndpointIsNotTruncatedAndBackOffersContinueOrDiscard() {
        val fixture = fixture(Route.MCP)
        rule.setContent { FixtureTheme { SettingsRoutes(fixture) } }
        replace("tool-server-label", "受控草稿")
        val oversized = "$ENDPOINT/" + "x".repeat(2048)
        replace("tool-server-endpoint", oversized)
        Espresso.closeSoftKeyboard()
        assertEquals(oversized, fixture.editor.state.value.server?.draft?.endpoint)
        assertEquals(oversized, editable("tool-server-endpoint"))
        scroll(MCP_LIST, "tool-settings-save").assertIsNotEnabled()
        val invalid = "$ENDPOINT invalid"
        replace("tool-server-endpoint", invalid)
        Espresso.closeSoftKeyboard()
        scroll(MCP_LIST, "tool-server-endpoint").assertIsDisplayed()
        screenshot("tool-invalid-endpoint-200")
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.onNodeWithText(text(R.string.tool_settings_continue)).performScrollTo()
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(Route.MCP, fixture.route.value)
        assertEquals(invalid, fixture.editor.state.value.server?.draft?.endpoint)
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.onNodeWithText(text(R.string.tool_settings_discard)).performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        screenshot("tool-unsaved-back-200", dialog = true)
        rule.onNodeWithText(text(R.string.tool_settings_discard)).performClick()
        rule.waitUntil { fixture.route.value == Route.LIST }
        assertFalse(fixture.editor.state.value.server?.dirty == true)
        assertTrue(fixture.store.state.value.snapshot!!.servers.isEmpty())
    }

    @Test fun failedStoragePreservesTheDraftAndAnExplicitRetryCanSave() {
        val fixture = fixture(Route.MCP)
        rule.setContent { FixtureTheme { SettingsRoutes(fixture) } }
        replace("tool-server-label", "保存失败仍保留草稿")
        replace("tool-server-endpoint", ENDPOINT)
        Espresso.closeSoftKeyboard()
        fixture.vault.failWrites.set(true)
        scroll(MCP_LIST, "tool-settings-save").performClick()
        rule.waitUntil(10_000) { fixture.editor.state.value.problem?.code == ToolProblemCode.STORAGE }
        assertEquals(ENDPOINT, fixture.editor.state.value.server?.draft?.endpoint)
        assertTrue(fixture.editor.state.value.server!!.dirty)
        rule.onNodeWithText(text(R.string.tool_settings_load_failed)).assertIsDisplayed()
        fixture.vault.failWrites.set(false)
        storageRecoveryEvidence("tool-storage-before-retry-scroll", fixture)
        val readsBeforeRetry = fixture.vault.readCalls.get()
        rule.onNodeWithText(text(R.string.tool_settings_retry)).performScrollTo()
            .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        storageRecoveryEvidence("tool-storage-before-retry-click", fixture)
        rule.onNodeWithText(text(R.string.tool_settings_retry)).performClick()
        storageRecoveryEvidence("tool-storage-after-retry-click", fixture)
        rule.waitUntil(10_000) { fixture.store.state.value.problem == null && !fixture.store.state.value.loading }
        assertTrue("The visible retry must actually re-read the vault", fixture.vault.readCalls.get() > readsBeforeRetry)
        storageRecoveryEvidence("tool-storage-reloaded", fixture)
        scroll(MCP_LIST, "tool-settings-save").assertIsEnabled().performClick()
        rule.waitUntil(10_000) { fixture.editor.state.value.savedNotice && fixture.editor.state.value.pending == null }
        assertEquals("保存失败仍保留草稿", fixture.store.state.value.snapshot!!.servers.single().label)
    }

    @Test fun backgroundClearsSecretButNotTheDraftAndSavedKeysNeverReadBack() {
        val fixture = fixture(Route.SEARCH)
        rule.setContent { FixtureTheme { SettingsRoutes(fixture) } }
        scroll(SEARCH_LIST, "tool-provider-exa_api_key").performClick()
        scroll(SEARCH_LIST, "tool-credential-replace").performClick()
        scroll(SEARCH_LIST, "tool-secret").performTextReplacement("synthetic-settings-key")
        Espresso.closeSoftKeyboard()
        assertFalse(fixture.search.state.value.toString().contains("synthetic-settings-key"))
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        scroll(SEARCH_LIST, "tool-secret").assertIsDisplayed()
        assertEquals("", editable("tool-secret"))
        assertEquals(SearchProvider.EXA_API_KEY, fixture.search.state.value.search?.draft?.provider)
        scroll(SEARCH_LIST, "tool-settings-save").assertIsNotEnabled()
        scroll(SEARCH_LIST, "tool-secret").performTextReplacement("synthetic-settings-key")
        Espresso.closeSoftKeyboard()
        scroll(SEARCH_LIST, "tool-settings-save").performClick()
        rule.waitUntil(10_000) { fixture.search.state.value.savedNotice && fixture.search.state.value.pending == null }
        assertEquals(ToolCredentialState.CONFIGURED, fixture.store.state.value.snapshot?.web?.credentialState)
        scroll(SEARCH_LIST, "tool-credential-replace").performClick()
        scroll(SEARCH_LIST, "tool-secret").assertIsDisplayed()
        assertEquals("Configured credentials must not be read into the form", "", editable("tool-secret"))
        screenshot("tool-secret-no-readback-200")
        scroll(SEARCH_LIST, "tool-credential-remove").performClick()
        scroll(SEARCH_LIST, "tool-settings-save").assertIsNotEnabled()
        scroll(SEARCH_LIST, "tool-provider-exa_keyless").performClick()
        scroll(SEARCH_LIST, "tool-settings-save").assertIsEnabled().performClick()
        rule.waitUntil(10_000) { fixture.store.state.value.snapshot?.web?.credentialState == ToolCredentialState.MISSING }
        assertEquals(SearchProvider.EXA_KEYLESS, fixture.store.state.value.snapshot?.web?.provider)
        assertEquals(0, fixture.provider.readinessCalls.get())
    }

    @Test fun readOnlyDiscoveryCanStopAndBackCancelsWithoutTrappingNavigation() {
        val fixture = fixture(Route.MCP)
        val server = fixture.seedServer()
        fixture.serverId.value = server.id
        fixture.provider.gate.set(CompletableDeferred())
        rule.setContent { FixtureTheme { SettingsRoutes(fixture) } }
        scroll(MCP_LIST, "tool-discover").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(10_000) { fixture.editor.state.value.checking && fixture.provider.readinessCalls.get() == 1 }
        scroll(MCP_LIST, "tool-discover-stop").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(10_000) {
            !fixture.editor.state.value.checking && fixture.service.discovery.value[server.id]?.loading != true
        }
        assertTrue(fixture.editor.state.value.discoveryCancelled)
        assertEquals(null, fixture.editor.state.value.problem)
        scroll(MCP_LIST, "tool-discover").assertIsEnabled()
        screenshot("tool-discovery-stopped-200")
        rule.onNodeWithTag("tool-discover").performClick()
        rule.waitUntil(10_000) { fixture.provider.readinessCalls.get() == 2 && fixture.editor.state.value.checking }
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.waitUntil(10_000) { fixture.route.value == Route.LIST && !fixture.editor.state.value.checking }
        assertEquals(null, fixture.service.discovery.value[server.id]?.report)
        assertEquals(server.revision, fixture.store.state.value.snapshot!!.servers.single().revision)
    }

    @Test fun realServiceDiscoveryFailureIsExplicitAndNeverCalledConnected() {
        val fixture = fixture(Route.MCP)
        val server = fixture.seedServer()
        fixture.serverId.value = server.id
        rule.setContent { FixtureTheme { SettingsRoutes(fixture) } }
        scroll(MCP_LIST, "tool-discover").performClick()
        rule.waitUntil(10_000) { fixture.service.discovery.value[server.id]?.problem != null && !fixture.editor.state.value.checking }
        assertEquals(ToolProblemCode.NETWORK, fixture.service.discovery.value[server.id]?.problem?.code)
        scroll(MCP_LIST, "tool-discovery-error").assertIsDisplayed()
        screenshot("tool-discovery-failure-200")
        rule.onAllNodesWithText("连接成功").assertCountEquals(0)
        rule.onAllNodesWithTag("tool-discovery-current").assertCountEquals(0)
        assertTrue(fixture.editor.state.value.server!!.draft.enabledTools.isEmpty())
        assertEquals(1, fixture.provider.readinessCalls.get())
    }

    @Test fun pendingSaveAllowsBackAndItsCompletionDoesNotNavigateAgain() {
        val fixture = fixture(Route.MCP)
        rule.setContent { FixtureTheme { SettingsRoutes(fixture) } }
        replace("tool-server-label", "受控后台保存")
        replace("tool-server-endpoint", ENDPOINT)
        Espresso.closeSoftKeyboard()
        val gate = CompletableDeferred<Unit>()
        fixture.vault.gate.set(gate)
        scroll(MCP_LIST, "tool-settings-save").performClick()
        rule.waitUntil(10_000) { fixture.vault.writeStarted.isCompleted }
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.waitUntil { fixture.route.value == Route.LIST }
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        gate.complete(Unit)
        rule.waitUntil(10_000) { fixture.store.state.value.snapshot?.servers?.size == 1 }
        assertEquals(Route.LIST, fixture.route.value)
        assertEquals("受控后台保存", fixture.store.state.value.snapshot!!.servers.single().label)
    }

    @Test fun externalConfigChangesDoNotOverwriteTheDraftOrPermitStaleSave() {
        val fixture = fixture(Route.MCP)
        val server = fixture.seedServer()
        fixture.serverId.value = server.id
        rule.setContent { FixtureTheme { SettingsRoutes(fixture) } }
        replace("tool-server-endpoint", "https://other.example.org/mcp")
        Espresso.closeSoftKeyboard()
        runBlocking {
            fixture.store.saveServer(server.id, server.revision, McpServerDraft(server).copy(label = "另一处保存的名称"))
        }
        scroll(MCP_LIST, "tool-settings-save").assertIsNotEnabled()
        assertEquals("https://other.example.org/mcp", fixture.editor.state.value.server?.draft?.endpoint)
        assertEquals(server.revision, fixture.editor.state.value.server?.expectedRevision)
        rule.onNodeWithTag(MCP_LIST).performScrollToNode(
            androidx.compose.ui.test.hasText(text(R.string.tool_settings_reset))
        )
        rule.onNodeWithText(text(R.string.tool_settings_reset)).performClick()
        rule.onNodeWithText(text(R.string.tool_settings_discard)).performScrollTo().performClick()
        rule.waitUntil(10_000) {
            fixture.editor.state.value.pending == null &&
                fixture.editor.state.value.server?.draft?.label == "另一处保存的名称"
        }
        assertEquals(
            fixture.store.state.value.snapshot!!.servers.single().revision,
            fixture.editor.state.value.server?.expectedRevision
        )
        assertEquals(ENDPOINT, fixture.editor.state.value.server?.draft?.endpoint)
    }

    /** Presentation contract only: protocol parsing is covered by the tools owner's transport tests. */
    @Test fun reportRowsRejectUnsupportedSchemasStaleRevisionsAndRateLimitSuccessClaims() {
        val fixture = fixture()
        val server = fixture.seedServer()
        val form = mutableStateOf(ToolMcpForm(server.id, server))
        val choices = AtomicInteger()
        val report = McpDiscoveryReport(
            server.id, server.revision, "2026-07-28", "受控服务",
            listOf(
                McpDiscoveredTool(
                    "controlled_read", "受控工具，服务提示只读并不构成授权",
                    buildJsonObject { put("type", "object") }, supported = true, enabled = false
                ),
                McpDiscoveredTool(
                    "unsupported_reference", "", null, supported = false, enabled = false,
                    problem = ToolProblem(ToolProblemCode.SCHEMA, "受控 schema 引用了不支持的远程资源")
                )
            ),
            0
        )
        val reportState = mutableStateOf(McpDiscoveryState(server.revision, report = report))
        rule.setContent {
            FixtureTheme {
                val canonical by fixture.store.state.collectAsStateWithLifecycle()
                PageScaffold(text(R.string.tool_settings_mcp_edit_title), {}) { page ->
                    LazyColumn(
                        page.testTag("tool-report-fixture"),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        mcpSettingsDiscoveryItems(
                            form.value, canonical.snapshot?.servers?.singleOrNull(), reportState.value,
                            working = false, usable = true, checking = false, cancelled = false,
                            onDiscover = {}, onCancel = {},
                            onToolSelection = { name, enabled ->
                                choices.incrementAndGet()
                                val current = form.value
                                form.value = current.copy(draft = current.draft.copy(
                                    enabledTools = if (enabled) current.draft.enabledTools + name else current.draft.enabledTools - name
                                ))
                            }
                        )
                    }
                }
            }
        }
        scroll("tool-report-fixture", "tool-choice-controlled_read").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(setOf("controlled_read"), form.value.draft.enabledTools)
        scroll("tool-report-fixture", "tool-unsupported-unsupported_reference").assertIsNotEnabled()
            .performTouchInput { click() }
        assertEquals(1, choices.get())
        screenshot("tool-unsupported-schema-200")
        val updated = runBlocking {
            fixture.store.saveServer(server.id, server.revision, McpServerDraft(server).copy(label = "更新后的受控服务"))
        }
        rule.onNodeWithTag("tool-report-fixture").performScrollToNode(
            androidx.compose.ui.test.hasText(text(R.string.tool_settings_discovery_outdated))
        )
        rule.onNodeWithText(text(R.string.tool_settings_discovery_outdated)).assertIsDisplayed()
        rule.onAllNodesWithTag("tool-choice-controlled_read").assertCountEquals(0)
        rule.runOnIdle {
            form.value = ToolMcpForm(updated.id, updated)
            reportState.value = McpDiscoveryState(
                updated.revision,
                problem = ToolProblem(ToolProblemCode.RATE_LIMITED, "受控服务要求稍后重试", true, 429)
            )
        }
        scroll("tool-report-fixture", "tool-discovery-error").assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(
            R.string.tool_settings_error_http, "受控服务要求稍后重试", 429
        )).assertIsDisplayed()
        rule.onAllNodesWithTag("tool-discovery-current").assertCountEquals(0)
        rule.onAllNodesWithText("连接成功").assertCountEquals(0)
        screenshot("tool-rate-limit-presentation-200")
    }

    private fun fixture(route: Route = Route.LIST): Fixture {
        val result = Fixture(route)
        fixtures += result
        rule.waitUntil(10_000) { !result.store.state.value.loading }
        assertEquals(null, result.store.state.value.problem)
        rule.runOnIdle { result.createViewModels() }
        return result
    }

    private fun replace(tag: String, value: String) {
        scroll(MCP_LIST, tag).performTextReplacement(value)
    }

    private fun scroll(list: String, tag: String) =
        rule.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)).let {
            rule.onNodeWithTag(tag).performScrollTo()
        }

    private fun editable(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun text(resource: Int) = rule.activity.getString(resource)

    private fun screenshot(name: String, dialog: Boolean = false) {
        val directory = File(rule.activity.getExternalFilesDir(null), "agent-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        saveNativeScreenshotEvidence(rule.activity, directory, name) {
            (if (dialog) rule.onNode(isDialog()) else rule.onRoot()).captureToImage().asAndroidBitmap()
        }
    }

    private fun storageRecoveryEvidence(name: String, fixture: Fixture) {
        val directory = File(rule.activity.getExternalFilesDir(null), "agent-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val targets = rule.onAllNodesWithText(
            text(R.string.tool_settings_retry), useUnmergedTree = true
        ).fetchSemanticsNodes().joinToString("\n") { node ->
            "retry placed=${node.layoutInfo.isPlaced}; position=${node.positionInWindow}; " +
                "size=${node.size}; visibleBounds=${node.boundsInWindow}"
        }
        val state = fixture.store.state.value
        val ui = fixture.editor.state.value
        val details = "uptime=${SystemClock.uptimeMillis()}; storeLoading=${state.loading}; " +
            "storeProblem=${state.problem?.code}; pending=${ui.pending}; uiProblem=${ui.problem?.code}; " +
            "dirty=${ui.server?.dirty}; reads=${fixture.vault.readCalls.get()}; writes=${fixture.vault.writeCalls.get()}"
        File(directory, "$name.txt").writeText(
            details + "\n" + targets + "\n" + rule.onRoot(useUnmergedTree = true).printToString()
        )
        screenshot(name)
    }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
            CopilotGoTheme(dynamicColor = false) { Surface(Modifier.fillMaxSize(), content = content) }
        }
    }

    @Composable
    private fun SettingsRoutes(fixture: Fixture) {
        when (fixture.route.value) {
            Route.LIST -> ToolSettingsScreen(
                fixture.list,
                onOpenSearch = { fixture.route.value = Route.SEARCH },
                onAddServer = { fixture.serverId.value = null; fixture.route.value = Route.MCP },
                onEditServer = { fixture.serverId.value = it; fixture.route.value = Route.MCP },
                onBack = {}
            )
            Route.SEARCH -> SearchToolSettingsScreen(fixture.search) { fixture.route.value = Route.LIST }
            Route.MCP -> McpServerSettingsScreen(
                fixture.editor, fixture.serverId.value, onBack = { fixture.route.value = Route.LIST }
            )
        }
    }

    private enum class Route { LIST, SEARCH, MCP }

    private class Fixture(initialRoute: Route) {
        val owner = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val vault = MemoryVault()
        val provider = BlockedProvider()
        val store = ToolSettingsStore(vault, scope)
        val service = RemoteMcpService(store, ToolHttpClient(provider), scope)
        val route = mutableStateOf(initialRoute)
        val serverId = mutableStateOf<String?>(null)
        lateinit var list: ToolSettingsViewModel
        lateinit var search: ToolSettingsViewModel
        lateinit var editor: ToolSettingsViewModel

        fun createViewModels() {
            val factory = SimpleVMFactory { ToolSettingsViewModel(store, service) }
            val provider = ViewModelProvider(owner, factory)
            list = provider["list", ToolSettingsViewModel::class.java]
            search = provider["search", ToolSettingsViewModel::class.java]
            editor = provider["server", ToolSettingsViewModel::class.java]
        }

        fun seedServer() = runBlocking {
            store.saveServer(null, null, McpServerDraft(label = "受控服务", endpoint = ENDPOINT))
        }
    }

    private class MemoryVault : SecretVault {
        private val records = ConcurrentHashMap<String, String>()
        val failWrites = AtomicBoolean()
        val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val writeStarted = CompletableDeferred<Unit>()
        val readCalls = AtomicInteger()
        val writeCalls = AtomicInteger()
        override suspend fun read(name: String): String? {
            readCalls.incrementAndGet()
            return records[name]
        }
        override suspend fun write(name: String, value: String) {
            writeCalls.incrementAndGet()
            gate.get()?.let {
                writeStarted.complete(Unit)
                it.await()
            }
            if (failWrites.get()) throw IOException("Controlled settings vault failure")
            records[name] = value
        }
    }

    private class BlockedProvider : HttpClientProvider {
        val readinessCalls = AtomicInteger()
        val dnsCalls = AtomicInteger()
        val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
        override val client = OkHttpClient.Builder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                dnsCalls.incrementAndGet()
                throw UnknownHostException("Controlled settings fixture forbids all DNS")
            }
        }).build()
        override suspend fun awaitReady() {
            readinessCalls.incrementAndGet()
            gate.get()?.await()
            throw IOException("Controlled settings fixture forbids network IO")
        }
    }

    private companion object {
        const val ENDPOINT = "https://example.org/mcp"
        const val MCP_LIST = "tool-mcp-settings"
        const val SEARCH_LIST = "tool-search-settings"
    }
}
