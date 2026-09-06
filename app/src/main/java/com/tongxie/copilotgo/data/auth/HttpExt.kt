package com.tongxie.copilotgo.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

internal suspend fun Call.executeAsync(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, discarded, _ -> discarded.close() }
            }
        })
    }

/** Owns the socket until body consumption finishes, not merely until headers arrive. */
suspend fun <T> Call.withResponse(block: suspend (Response) -> T): T =
    withContext(Dispatchers.IO) {
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    this@withResponse.cancel()
                }
            }
            try {
                this@withResponse.executeAsync().use { block(it) }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                cancellation.cancel()
            }
        }
    }
