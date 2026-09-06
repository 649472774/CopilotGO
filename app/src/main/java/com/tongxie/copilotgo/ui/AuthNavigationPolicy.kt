package com.tongxie.copilotgo.ui

import com.tongxie.copilotgo.data.auth.AuthState

internal fun authRedirect(state: AuthState, currentRoute: String?): String? {
    if (currentRoute == null) return null
    return when {
        (state is AuthState.NotLoggedIn || state is AuthState.AwaitingUserAuthorization) &&
            currentRoute != Routes.LOGIN -> Routes.LOGIN
        state is AuthState.LoggedIn && currentRoute == Routes.LOGIN -> Routes.CHAT_LIST
        else -> null
    }
}

// A failed credential clear is not a completed logout; leave its retry UI mounted.
internal fun canShowNativeContent(state: AuthState): Boolean =
    state is AuthState.LoggedIn || state is AuthState.Failed
