package com.tongxie.copilotgo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tongxie.copilotgo.AppContainer
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.ui.screens.ChatListScreen
import com.tongxie.copilotgo.ui.screens.ChatScreen
import com.tongxie.copilotgo.ui.screens.FilesScreen
import com.tongxie.copilotgo.ui.screens.LoginScreen
import com.tongxie.copilotgo.ui.screens.RemoteWebViewScreen
import com.tongxie.copilotgo.ui.screens.SettingsAboutScreen
import com.tongxie.copilotgo.ui.screens.SettingsAccountScreen
import com.tongxie.copilotgo.ui.screens.SettingsProxyScreen
import com.tongxie.copilotgo.ui.screens.SettingsScreen
import com.tongxie.copilotgo.ui.screens.SettingsStorageScreen
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel
import com.tongxie.copilotgo.ui.viewmodel.ChatViewModel
import com.tongxie.copilotgo.ui.viewmodel.ChatDraftsViewModel
import com.tongxie.copilotgo.ui.viewmodel.LibraryFilesViewModel
import com.tongxie.copilotgo.ui.viewmodel.ProxyViewModel
import com.tongxie.copilotgo.ui.viewmodel.ProxyFormViewModel
import com.tongxie.copilotgo.ui.viewmodel.SessionListViewModel
import com.tongxie.copilotgo.ui.viewmodel.UpdateViewModel
import com.tongxie.copilotgo.ui.components.ScreenState

object Routes {
    const val LOGIN = "login"
    const val CHAT_LIST = "chat_list"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"
    const val SETTINGS_ACCOUNT = "settings/account"
    const val SETTINGS_PROXY = "settings/proxy"
    const val SETTINGS_STORAGE = "settings/storage"
    const val SETTINGS_ABOUT = "settings/about"
    const val FILES = "files"
    const val REMOTE = "remote"

    fun chat(sessionId: String) = "chat/${android.net.Uri.encode(sessionId)}"
}

@Composable
fun AppNavigation(container: AppContainer) {
    val nav: NavHostController = rememberNavController()

    val authVm: AuthViewModel = viewModel(factory = SimpleVMFactory { AuthViewModel(container.authRepo) })
    val authState by authVm.state.collectAsStateWithLifecycle()
    val initializing by authVm.initializing.collectAsStateWithLifecycle()
    val currentEntry by nav.currentBackStackEntryAsState()

    val listVm: SessionListViewModel = viewModel(
        factory = SimpleVMFactory {
            SessionListViewModel(
                container.sessionStore,
                container.chatClient,
                container.authRepo,
                container.chatStreamCenter,
                catalog = container.modelCatalog
            )
        }
    )
    val proxyVm: ProxyViewModel = viewModel(
        factory = SimpleVMFactory { ProxyViewModel(container.proxySettings, container.healthChecker) }
    )
    val proxyFormVm: ProxyFormViewModel = viewModel()
    val draftsVm: ChatDraftsViewModel = viewModel(
        factory = SimpleVMFactory { ChatDraftsViewModel(container.appContext) }
    )
    val filesVm: LibraryFilesViewModel = viewModel(
        factory = SimpleVMFactory {
            LibraryFilesViewModel(container.appContext, container.sessionStore, draftsVm::discard)
        }
    )

    val updateVm: UpdateViewModel = viewModel(
        factory = SimpleVMFactory {
            UpdateViewModel(container.appContext, container.updateChecker, container.updatePrefs)
        }
    )

    fun open(route: String) {
        nav.navigate(route) { launchSingleTop = true }
    }

    fun openLogin() {
        if (authRedirect(authVm.state.value, nav.currentDestination?.route) == Routes.LOGIN) {
            nav.navigate(Routes.LOGIN) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    fun openAuthenticatedHome() {
        if (authVm.state.value is AuthState.LoggedIn && nav.currentDestination?.route == Routes.LOGIN) {
            nav.navigate(Routes.CHAT_LIST) {
                popUpTo(Routes.LOGIN) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    fun back() {
        if (!nav.popBackStack()) {
            open(if (canShowNativeContent(authVm.state.value)) Routes.CHAT_LIST else Routes.LOGIN)
        }
    }

    LaunchedEffect(initializing, authState, currentEntry?.destination?.route) {
        if (!initializing && currentEntry != null) {
            when (authRedirect(authState, currentEntry?.destination?.route)) {
                Routes.LOGIN -> openLogin()
                Routes.CHAT_LIST -> openAuthenticatedHome()
            }
        }
    }

    if (initializing) {
        ScreenState(
            title = stringResource(R.string.auth_restoring),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            loading = true
        )
        return
    }

    NavHost(navController = nav, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(
                viewModel = authVm,
                onLoggedIn = ::openAuthenticatedHome
            )
        }
        composable(Routes.CHAT_LIST) {
            if (!canShowNativeContent(authState)) return@composable
            ChatListScreen(
                viewModel = listVm,
                updateVm = updateVm,
                filesVm = filesVm,
                onOpen = { id -> open(Routes.chat(id)) },
                onSettings = { open(Routes.SETTINGS) },
                onFiles = { open(Routes.FILES) },
                onRemote = { open(Routes.REMOTE) }
            )
        }
        composable(Routes.CHAT) { backStackEntry ->
            if (!canShowNativeContent(authState)) return@composable
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            if (sessionId == null || !sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
                ScreenState(
                    title = stringResource(R.string.chat_invalid_route),
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    actionLabel = stringResource(R.string.action_back),
                    onAction = ::back
                )
            } else {
                val chatVm: ChatViewModel = viewModel(
                    key = sessionId,
                    factory = SimpleVMFactory {
                        ChatViewModel(sessionId, container.chatStreamCenter)
                    }
                )
                ChatScreen(
                    sessionId = sessionId,
                    viewModel = chatVm,
                    modelsVm = listVm,
                    draftsVm = draftsVm,
                    filesVm = filesVm,
                    onBack = ::back
                )
            }
        }
        composable(Routes.SETTINGS) {
            if (!canShowNativeContent(authState)) return@composable
            SettingsScreen(
                authVm = authVm,
                proxyVm = proxyVm,
                onOpenAccount = { open(Routes.SETTINGS_ACCOUNT) },
                onOpenProxy = { open(Routes.SETTINGS_PROXY) },
                onOpenStorage = { open(Routes.SETTINGS_STORAGE) },
                onOpenAbout = { open(Routes.SETTINGS_ABOUT) },
                onBack = ::back
            )
        }
        composable(Routes.SETTINGS_ACCOUNT) {
            if (!canShowNativeContent(authState)) return@composable
            SettingsAccountScreen(
                authVm = authVm,
                onLoggedOut = ::openLogin,
                onBack = ::back
            )
        }
        composable(Routes.SETTINGS_PROXY) {
            if (!canShowNativeContent(authState)) return@composable
            SettingsProxyScreen(
                proxyVm = proxyVm,
                formVm = proxyFormVm,
                onBack = ::back
            )
        }
        composable(Routes.SETTINGS_STORAGE) {
            if (!canShowNativeContent(authState)) return@composable
            SettingsStorageScreen(
                paths = container.paths,
                onOpenFiles = { open(Routes.FILES) },
                onBack = ::back
            )
        }
        composable(Routes.SETTINGS_ABOUT) {
            if (!canShowNativeContent(authState)) return@composable
            SettingsAboutScreen(
                updateVm = updateVm,
                onBack = ::back
            )
        }
        composable(Routes.FILES) {
            if (!canShowNativeContent(authState)) return@composable
            FilesScreen(
                filesVm = filesVm,
                sessionsVm = listVm,
                onOpenSession = { open(Routes.chat(it)) },
                onBack = ::back
            )
        }
        composable(Routes.REMOTE) {
            if (!canShowNativeContent(authState)) return@composable
            RemoteWebViewScreen(
                onBack = ::back,
                proxyConfig = container.proxySettings.config,
                proxyInitialized = container.proxySettings.initialized,
                proxyLoadError = container.proxySettings.loadError
            )
        }
    }
}

class SimpleVMFactory<T : ViewModel>(private val creator: () -> T) : ViewModelProvider.Factory {
    override fun <U : ViewModel> create(modelClass: Class<U>): U = modelClass.cast(creator())
}
