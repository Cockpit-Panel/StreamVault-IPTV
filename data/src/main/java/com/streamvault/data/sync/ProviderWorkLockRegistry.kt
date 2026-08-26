package com.streamvault.data.sync

import com.streamvault.domain.util.KeyedMutexRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides one in-process execution lane per provider, regardless of whether work originated
 * from foreground refresh, catalog indexing, EPG, or a WorkManager recovery entry point.
 */
@Singleton
class ProviderWorkLockRegistry @Inject constructor() {
    private val admissionMutex = Mutex()
    private val providerMutexes = KeyedMutexRegistry<Long>()
    private val admittedCount = AtomicInteger(0)

    fun isAnyWorkActiveOrWaiting(): Boolean = admittedCount.get() > 0

    suspend fun <T> withProviderLock(providerId: Long, block: suspend () -> T): T {
        require(providerId > 0L) { "Provider work requires a positive provider ID." }
        admissionMutex.withLock {
            admittedCount.incrementAndGet()
        }
        return try {
            providerMutexes.withLock(providerId, block)
        } finally {
            admissionMutex.withLock {
                admittedCount.decrementAndGet()
            }
        }
    }

    suspend fun runWhenNoWorkActive(block: suspend () -> Boolean): Boolean =
        admissionMutex.withLock {
            if (admittedCount.get() > 0) {
                false
            } else {
                block()
            }
        }

    /** Removes idle provider state after durable provider deletion. */
    suspend fun forgetProvider(providerId: Long) {
        admissionMutex.withLock {
            providerMutexes.forget(providerId)
        }
    }
}
