package com.tongxie.copilotgo.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.data.auth.CopilotTokenClient
import com.tongxie.copilotgo.data.auth.CredentialStore
import com.tongxie.copilotgo.data.auth.DeviceFlowClient
import com.tongxie.copilotgo.data.auth.StoredCredentials
import com.tongxie.copilotgo.data.auth.TokenStore
import com.tongxie.copilotgo.data.chat.SessionSummary
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.proxy.ProxyHealthChecker
import com.tongxie.copilotgo.data.proxy.ProxySettingsStore
import com.tongxie.copilotgo.data.storage.SecretVault
import com.tongxie.copilotgo.ui.screens.LoginScreen
import com.tongxie.copilotgo.ui.screens.SessionListContent
import com.tongxie.copilotgo.ui.screens.SettingsAccountScreen
import com.tongxie.copilotgo.ui.screens.SettingsProxyScreen
import com.tongxie.copilotgo.ui.screens.SettingsScreen
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme
import com.tongxie.copilotgo.ui.viewmodel.AccountActionsViewModel
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel
import com.tongxie.copilotgo.ui.viewmodel.ProxyFormViewModel
import com.tongxie.copilotgo.ui.viewmodel.ProxyViewModel
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native screens/VMs, backed only by isolated in-memory fixtures. */
@RunWith(AndroidJUnit4::class)
class NativeSettingsAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val fixtures = mutableListOf<Fixture>()

    @Before fun prepareWindow() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @After fun releaseFixtures() {
        rule.runOnIdle { fixtures.forEach { it.owner.clear() } }
        fixtures.forEach { fixture ->
            fixture.scope.cancel()
            assertEquals("Fixtures must never issue network traffic", 0, fixture.networkCalls.get())
            fixture.preferenceNames.forEach { rule.activity.deleteSharedPreferences(it) }
        }
    }

    @Test fun loginRemainsScrollableAndActionableAt200Percent() {
        val fixture = fixture()
        rule.setContent { FixtureTheme(fixture) { LoginScreen(fixture.authVm, onLoggedIn = {}) } }
        rule.onNodeWithText(text(R.string.settings_login_start)).performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        saveScreenshot("login-200")
        rule.onNodeWithText(text(R.string.settings_terms_title)).performScrollTo().assertIsDisplayed()
    }

    @Test fun proxyValidationAndFailedSavePreserveTheEditableDraft() {
        val fixture = fixture()
        rule.setContent {
            FixtureTheme(fixture) {
                SettingsProxyScreen(fixture.proxyVm, onBack = {}, formVm = fixture.formVm)
            }
        }
        val port = rule.onNode(hasSetTextAction() and hasText(text(R.string.settings_proxy_port)))
        port.performScrollTo().performTextReplacement("78x90")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithText(text(R.string.settings_action_save)).performScrollTo().assertIsNotEnabled()
        assertEquals("78x90", fixture.formVm.state.value.draft?.portText)
        port.performScrollTo().performTextReplacement("8080")
        Espresso.closeSoftKeyboard()
        fixture.vault.failWrites.set(true)
        rule.onNodeWithText(text(R.string.settings_action_save)).performScrollTo().performClick()
        rule.waitUntil(5_000) { fixture.formVm.state.value.saveFailed }
        assertEquals("8080", fixture.formVm.state.value.draft?.portText)
        assertTrue(fixture.formVm.state.value.dirty)
        rule.onNodeWithText(text(R.string.settings_proxy_save_failed)).performScrollTo().assertIsDisplayed()
        saveScreenshot("proxy-failure-200")
        fixture.vault.failWrites.set(false)
        rule.onNodeWithText(text(R.string.settings_action_save)).performScrollTo().performClick()
        rule.waitUntil(5_000) { fixture.formVm.state.value.savedNotice && !fixture.formVm.state.value.dirty }
        assertEquals(8080, fixture.proxyStore.config.value.port)
    }

    @Test fun proxyBackOffersContinueOrExplicitDiscard() {
        val fixture = fixture()
        val back = AtomicInteger()
        rule.setContent {
            FixtureTheme(fixture) {
                SettingsProxyScreen(fixture.proxyVm, onBack = { back.incrementAndGet() }, formVm = fixture.formVm)
            }
        }
        rule.onNode(hasSetTextAction() and hasText(text(R.string.settings_proxy_port)))
            .performScrollTo().performTextReplacement("wrong")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.onNodeWithText(text(R.string.settings_proxy_continue_editing))
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle {
            assertEquals(0, back.get())
            assertEquals("wrong", fixture.formVm.state.value.draft?.portText)
        }
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        saveScreenshot("proxy-unsaved-dialog-200", dialog = true)
        val discard = rule.onNodeWithText(text(R.string.settings_proxy_discard))
        discard.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        saveScreenshot("proxy-unsaved-dialog-actions-200", dialog = true)
        discard.performClick()
        rule.waitUntil(5_000) { back.get() == 1 }
        rule.runOnIdle {
            assertEquals(1, back.get())
            assertEquals("7890", fixture.formVm.state.value.draft?.portText)
        }
    }

    @Test fun accountDoesNotNavigateWhenCredentialClearFails() {
        val fixture = fixture(signedIn = true)
        val navigated = AtomicInteger()
        rule.setContent {
            FixtureTheme(fixture) {
                SettingsAccountScreen(
                    fixture.authVm, onLoggedOut = { navigated.incrementAndGet() },
                    onBack = {}, actionsVm = fixture.accountVm
                )
            }
        }
        fixture.credentials.failWrites.set(true)
        rule.onNodeWithText(text(R.string.settings_account_logout)).performScrollTo().performClick()
        rule.onAllNodesWithText(text(R.string.settings_account_logout)).onLast().performClick()
        rule.waitUntil(5_000) { fixture.accountVm.state.value == AccountActionsViewModel.State.Failed }
        assertTrue(fixture.authVm.state.value is AuthState.Failed)
        assertEquals(0, navigated.get())
        rule.waitUntil(5_000) {
            rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        saveScreenshot("account-clear-failure-200", dialog = true)
        rule.onNode(
            hasText(text(R.string.settings_account_logout_failed)) and hasAnyAncestor(isDialog())
        ).assertIsDisplayed()
        fixture.credentials.failWrites.set(false)
        rule.onNodeWithText(text(R.string.settings_action_retry)).performClick()
        rule.waitUntil(5_000) { navigated.get() == 1 }
        assertEquals(AuthState.NotLoggedIn, fixture.authVm.state.value)
    }

    @Test fun settingsExposeOneToggleTargetAtLargeFont() {
        val fixture = fixture(signedIn = true)
        rule.setContent {
            FixtureTheme(fixture) {
                SettingsScreen(
                    fixture.authVm, fixture.proxyVm,
                    onOpenAccount = {}, onOpenProxy = {}, onOpenStorage = {}, onOpenAbout = {}, onBack = {}
                )
            }
        }
        val toggle = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.waitUntil(5_000) { rule.onAllNodes(toggle).fetchSemanticsNodes().size == 1 }
        rule.onAllNodes(toggle).assertCountEquals(1)
        rule.onNode(toggle).performScrollTo().assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        saveScreenshot("settings-200")
    }

    @Test fun sessionListMatchesTheActualWindowWithLongChineseTitles() {
        val fixture = fixture()
        val sessions = (0..4).map {
            SessionSummary(
                id = "controlled-list-$it",
                title = "受控中文长标题与表情 ".repeat(6) + "\uD83D\uDE80 $it",
                model = "a-long-but-controlled-model-name-for-layout",
                createdAt = 0, updatedAt = 0, pinned = it == 0, revision = 0,
                messageCount = 12, preview = "只使用受控内容，不读取真实会话。", hasImages = false
            )
        }
        rule.setContent {
            FixtureTheme(fixture) {
                SessionListContent(
                    sessions, loading = false, error = null, busy = false,
                    snackbar = remember { SnackbarHostState() },
                    onOpen = {}, onReload = {}, onNew = {}, onSettings = {}, onFiles = {}, onRemote = {},
                    onTogglePin = {}, onRename = {}, onShare = {}, onDeleteRequest = {}
                )
            }
        }
        if (rule.activity.resources.configuration.screenWidthDp >= 840) {
            rule.onNodeWithText(text(R.string.remote_short_title)).assertIsDisplayed()
        } else {
            rule.onNodeWithContentDescription(text(R.string.app_menu)).assertIsDisplayed()
        }
        saveScreenshot("session-list-current-window-200")
    }

    private fun fixture(signedIn: Boolean = false): Fixture {
        lateinit var result: Fixture
        rule.runOnIdle {
            result = Fixture(rule.activity, signedIn)
            fixtures += result
        }
        rule.waitUntil(10_000) { !result.authVm.initializing.value && result.proxyStore.initialized.value }
        assertTrue(result.proxyStore.loadError.value == null)
        if (signedIn) assertTrue(result.authVm.state.value is AuthState.LoggedIn)
        return result
    }

    private fun text(resource: Int) = rule.activity.getString(resource)

    private fun saveScreenshot(name: String, dialog: Boolean = false) {
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        saveNativeScreenshotEvidence(rule.activity, directory, name) {
            (if (dialog) rule.onNode(isDialog()) else rule.onRoot()).captureToImage().asAndroidBitmap()
        }
    }

    @Composable
    private fun FixtureTheme(fixture: Fixture, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalContext provides fixture.context,
            LocalDensity provides Density(density.density, 2f)
        ) {
            CopilotGoTheme(dynamicColor = false) { Surface(Modifier.fillMaxSize(), content = content) }
        }
    }

    private class Fixture(target: Context, signedIn: Boolean) {
        val owner = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val networkCalls = AtomicInteger()
        val preferenceNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val id = UUID.randomUUID().toString()
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val fixtureName = "native-settings-$id-$name"
                preferenceNames += fixtureName
                return super.getSharedPreferences(fixtureName, mode)
            }
        }
        val credentials = MemoryCredentials(if (signedIn) StoredCredentials(
            githubToken = "controlled-native-credential",
            copilot = TokenStore.CachedCopilot(
                "controlled-native-copilot", System.currentTimeMillis() / 1000 + 3600,
                "controlled-subscription", "https://api.enterprise.githubcopilot.com"
            )
        ) else StoredCredentials())
        private val provider = object : HttpClientProvider {
            override val client = OkHttpClient.Builder().addInterceptor {
                networkCalls.incrementAndGet()
                throw IOException("Native fixture forbids network access")
            }.build()
        }
        private val json = Json { ignoreUnknownKeys = true }
        private val auth = AuthRepository(credentials, DeviceFlowClient(provider, json), CopilotTokenClient(provider, json))
        val vault = MemoryVault()
        val proxyStore = ProxySettingsStore(MemoryPreferences(), vault, scope)
        val authVm = ViewModelProvider(owner, SimpleVMFactory { AuthViewModel(auth) })[AuthViewModel::class.java]
        val proxyVm = ViewModelProvider(owner, SimpleVMFactory {
            ProxyViewModel(proxyStore, ProxyHealthChecker(provider, auth))
        })[ProxyViewModel::class.java]
        val formVm = ViewModelProvider(owner, SimpleVMFactory { ProxyFormViewModel(SavedStateHandle()) })[ProxyFormViewModel::class.java]
        val accountVm = ViewModelProvider(owner, SimpleVMFactory { AccountActionsViewModel() })[AccountActionsViewModel::class.java]
    }

    private class MemoryCredentials(initial: StoredCredentials) : CredentialStore {
        private val value = AtomicReference(initial)
        val failWrites = AtomicBoolean(false)
        override suspend fun readCredentials(): StoredCredentials = value.get()
        override suspend fun writeCredentials(credentials: StoredCredentials) {
            if (failWrites.get()) throw IOException("Controlled credential persistence failure")
            value.set(credentials)
        }
    }

    private class MemoryVault : SecretVault {
        private val values = ConcurrentHashMap<String, String>()
        val failWrites = AtomicBoolean(false)
        override suspend fun read(name: String): String? = values[name]
        override suspend fun write(name: String, value: String) {
            if (failWrites.get()) throw IOException("Controlled proxy persistence failure")
            values[name] = value
        }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(data.value).also { data.value = it }
        }
    }
}
