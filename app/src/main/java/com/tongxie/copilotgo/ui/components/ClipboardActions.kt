package com.tongxie.copilotgo.ui.components

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CancellationException

internal enum class TextCopyResult { Copied, TooLong, Unavailable }

internal fun copyText(clipboard: ClipboardManager, text: String): TextCopyResult {
    if (text.length > 100_000) return TextCopyResult.TooLong
    return try {
        clipboard.setText(AnnotatedString(text))
        TextCopyResult.Copied
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        TextCopyResult.Unavailable
    } catch (_: IllegalStateException) {
        TextCopyResult.Unavailable
    } catch (_: IllegalArgumentException) {
        TextCopyResult.Unavailable
    }
}
