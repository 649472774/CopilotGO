package com.tongxie.copilotgo.ui

import com.tongxie.copilotgo.data.auth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthNavigationPolicyTest {
    @Test fun failedCredentialClearKeepsTheAccountRetryScreen() {
        val failed = AuthState.Failed("Controlled credential clear failure")
        assertNull(authRedirect(failed, Routes.SETTINGS_ACCOUNT))
        assertTrue(canShowNativeContent(failed))
    }

    @Test fun onlyTerminalSignedOutStateLeavesProtectedContent() {
        assertEquals(Routes.LOGIN, authRedirect(AuthState.NotLoggedIn, Routes.SETTINGS_ACCOUNT))
        assertFalse(canShowNativeContent(AuthState.NotLoggedIn))
        assertNull(authRedirect(AuthState.NotLoggedIn, Routes.LOGIN))
    }

    @Test fun authenticatedLoginTransitionsOnceWithoutChangingOtherRoutes() {
        val authenticated = AuthState.LoggedIn("fixture")
        assertEquals(Routes.CHAT_LIST, authRedirect(authenticated, Routes.LOGIN))
        assertNull(authRedirect(authenticated, Routes.SETTINGS_PROXY))
        assertNull(authRedirect(authenticated, null))
    }
}
