package com.tongxie.copilotgo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tongxie.copilotgo.AppContainer
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.ui.screens.ChatListScreen
import com.tongxie.copilotgo.ui.screens.ChatScreen
import com.tongxie.copilotgo.ui.screens.FilesScreen
import com.tongxie.copilotgo.ui.screens.LoginScreen
import com.tongxie.copilotgo.ui.screens.SettingsAboutScreen
import com.tongxie.copilotgo.ui.screens.SettingsAccountScreen
import com.tongxie.copilotgo.ui.screens.SettingsProxyScreen
import com.tongxie.copilotgo.ui.screens.SettingsScreen
import com.tongxie.copilotgo.ui.screens.SettingsStorageScreen
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel
import com.tongxie.copilotgo.ui.viewmodel.ChatViewModel
import com.tongxie.copilotgo.ui.viewmodel.ProxyViewModel
import com.tongxie.copilotgo.ui.viewmodel.SessionListViewModel

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

    fun chat(sessionId: String) = "chat/$sessionId"
}

@Composable
fun AppNavigation(container: AppContainer) {
    val nav: NavHostController = rememberNavController()

    val authVm: AuthViewModel = viewModel(factory = SimpleVMFactory { AuthViewModel(container.authRepo) })
    val authState by authVm.state.collectAsState()

    val startDest = when (authState) {
        is AuthState.LoggedIn -> Routes.CHAT_LIST
        else -> Routes.LOGIN
    }

    NavHost(navController = nav, startDestination = startDest) {
        composable(Routes.LOGIN) {
            LoginScreen(
                viewModel = authVm,
                onLoggedIn = {
                    nav.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.CHAT_LIST) {
            val listVm: SessionListViewModel = viewModel(
                factory = SimpleVMFactory {
                    SessionListViewModel(container.sessionStore, container.chatClient, container.authRepo, container.chatStreamCenter)
                }
            )
            ChatListScreen(
                viewModel = listVm,
                onOpen = { id -> nav.navigate(Routes.chat(id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onFiles = { nav.navigate(Routes.FILES) }
            )
        }
        composable(Routes.CHAT) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val listVm: SessionListViewModel = viewModel(
                factory = SimpleVMFactory {
                    SessionListViewModel(container.sessionStore, container.chatClient, container.authRepo, container.chatStreamCenter)
                }
            )
            val chatVm: ChatViewModel = viewModel(
                key = sessionId,
                factory = SimpleVMFactory {
                    ChatViewModel(sessionId, container.chatStreamCenter)
                }
            )
            ChatScreen(
                viewModel = chatVm,
                modelsVm = listVm,
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            val proxyVm: ProxyViewModel = viewModel(
                factory = SimpleVMFactory { ProxyViewModel(container.proxySettings, container.healthChecker) }
            )
            SettingsScreen(
                authVm = authVm,
                proxyVm = proxyVm,
                onOpenAccount = { nav.navigate(Routes.SETTINGS_ACCOUNT) },
                onOpenProxy = { nav.navigate(Routes.SETTINGS_PROXY) },
                onOpenStorage = { nav.navigate(Routes.SETTINGS_STORAGE) },
                onOpenAbout = { nav.navigate(Routes.SETTINGS_ABOUT) },
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.SETTINGS_ACCOUNT) {
            SettingsAccountScreen(
                authVm = authVm,
                onLoggedOut = {
                    // Bug 3 修复：popUpTo(0) 在 Navigation 3 里是"不 pop"的 no-op，导致从设置
                    // 退出登录后 Back 能回到带缓存的 ChatListScreen，触发 401。
                    // 改成 pop 到 graph 起点（含起点）+ 重新 navigate 到 LOGIN，确保 back-stack 只剩 LOGIN。
                    nav.navigate(Routes.LOGIN) {
                        popUpTo(nav.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.SETTINGS_PROXY) {
            val proxyVm: ProxyViewModel = viewModel(
                factory = SimpleVMFactory { ProxyViewModel(container.proxySettings, container.healthChecker) }
            )
            SettingsProxyScreen(
                proxyVm = proxyVm,
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.SETTINGS_STORAGE) {
            SettingsStorageScreen(
                paths = container.paths,
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.SETTINGS_ABOUT) {
            SettingsAboutScreen(
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.FILES) {
            FilesScreen(
                paths = container.paths,
                onBack = { nav.popBackStack() }
            )
        }
    }
}

class SimpleVMFactory<T : ViewModel>(private val creator: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <U : ViewModel> create(modelClass: Class<U>): U = creator() as U
}
