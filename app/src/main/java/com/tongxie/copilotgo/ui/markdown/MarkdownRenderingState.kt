package com.tongxie.copilotgo.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class MarkdownInput(val source: String, val streaming: Boolean)

@Composable
internal fun rememberMarkdownDocument(markdown: String, isStreaming: Boolean): MarkdownDocument? {
    val requests = remember { Channel<MarkdownInput>(Channel.CONFLATED) }
    LaunchedEffect(markdown, isStreaming) {
        requests.trySend(MarkdownInput(markdown, isStreaming))
    }
    DisposableEffect(requests) {
        onDispose { requests.close() }
    }
    val document by produceState<MarkdownDocument?>(null, requests) {
        val parser = MarkdownParser()
        for (input in requests) {
            var latest = input
            if (latest.streaming) delay(100)
            while (true) {
                latest = requests.tryReceive().getOrNull() ?: break
            }
            // No collectLatest/cancel-and-restart: a final input is always parsed, even after a burst.
            value = withContext(Dispatchers.Default) { parser.parse(latest.source) }
        }
    }
    return document
}
