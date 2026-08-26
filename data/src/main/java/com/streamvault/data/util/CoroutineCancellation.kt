package com.streamvault.data.util

import kotlinx.coroutines.CancellationException

/** Kotlin's standard runCatching catches coroutine cancellation; suspend owners must not. */
internal suspend inline fun <T> runSuspendCatching(
    block: suspend () -> T
): kotlin.Result<T> = try {
    kotlin.Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    kotlin.Result.failure(error)
}

internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
