package com.tongxie.copilotgo.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tongxie.copilotgo.R

/** Unsaved credentials are composition-local, never a form model or saved-state value. */
@Stable
internal class ToolSecretInputState(private val maximumLength: Int) {
    init { require(maximumLength > 0) }

    var value by mutableStateOf("")
        private set
    var rejectedInput by mutableStateOf(false)
        private set
    var needsReentry by mutableStateOf(false)
        private set

    fun edit(value: String) {
        if (value.length > maximumLength || value.any(Char::isISOControl)) {
            rejectedInput = true
            return
        }
        this.value = value
        rejectedInput = false
        needsReentry = false
    }

    fun clear(forBackground: Boolean = false) {
        needsReentry = forBackground && (value.isNotEmpty() || needsReentry)
        value = ""
        rejectedInput = false
    }

    override fun toString(): String = "ToolSecretInputState(redacted)"
}

@Composable
internal fun rememberToolSecretInput(identity: Any, maximumLength: Int): ToolSecretInputState {
    val state = remember(identity, maximumLength) { ToolSecretInputState(maximumLength) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.clear(forBackground = true)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.clear()
        }
    }
    return state
}

@Composable
internal fun ToolSecretInput(
    state: ToolSecretInputState,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = state.value,
        onValueChange = state::edit,
        label = { Text(label) },
        supportingText = {
            Text(stringResource(when {
                state.rejectedInput -> R.string.tool_secret_invalid
                state.needsReentry -> R.string.tool_secret_reenter
                else -> R.string.tool_secret_ephemeral
            }))
        },
        isError = state.rejectedInput || state.needsReentry,
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier.fillMaxWidth().testTag("tool-secret")
    )
}
