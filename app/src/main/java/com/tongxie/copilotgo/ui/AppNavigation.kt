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
import com.tongxie.copilotgo.ui.screens.SettingsScreen
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel
import com.tongxie.copilotgo.ui.viewmodel.ChatViewModel
import com.tongxie.copilotgo.ui.viewmodel.SessionListViewModel

object Routes {
    const val LOGIN = "login"
    const val CHAT_LIST = "chat_list"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"
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
                    SessionListViewModel(container.sessionStore, container.chatClient, container.authRepo)
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
                    SessionListViewModel(container.sessionStore, container.chatClient, container.authRepo)
                }
            )
            val chatVm: ChatViewModel = viewModel(
                key = sessionId,
                factory = SimpleVMFactory {
                    ChatViewModel(sessionId, container.sessionStore, container.chatClient)
                }
            )
            ChatScreen(
                viewModel = chatVm,
                modelsVm = listVm,
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                authVm = authVm,
                paths = container.paths,
                onLoggedOut = {
                    nav.navigate(Routes.LOGIN) {
                        popUpTo(0)
                    }
                },
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
