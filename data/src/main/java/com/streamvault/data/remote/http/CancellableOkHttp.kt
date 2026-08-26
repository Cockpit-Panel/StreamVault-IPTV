package com.streamvault.data.remote.http

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Owns an OkHttp response for the complete lifetime of [block].
 *
 * The cancellation watcher remains installed after response headers arrive, so cancellation
 * also interrupts a thread blocked while reading the response body.
 */
suspend inline fun <T> Call.useCancellableResponse(
    block: suspend (Response) -> T
): T {
    val owned = openCancellableResponse()
    try {
        return block(owned.response)
    } catch (error: Throwable) {
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        owned.close()
    }
}

@PublishedApi
internal class CancellableHttpResponse internal constructor(
    val response: Response,
    private val cancellationWatcher: Job
) : AutoCloseable {
    override fun close() {
        response.close()
        cancellationWatcher.cancel()
    }
}

@PublishedApi
internal suspend fun Call.openCancellableResponse(): CancellableHttpResponse {
    val call = this
    val parentJob = currentCoroutineContext()[Job]
        ?: error("Cancellable HTTP calls require a coroutine Job")
    val cancellationWatcher = CoroutineScope(parentJob + Dispatchers.Unconfined).launch(
        start = CoroutineStart.UNDISPATCHED
    ) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    return try {
        CancellableHttpResponse(call.awaitResponseHeaders(), cancellationWatcher)
    } catch (error: Throwable) {
        cancellationWatcher.cancelAndJoin()
        throw error
    }
}

private suspend fun Call.awaitResponseHeaders(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
