package com.streamvault.domain.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-key serialization without retaining every key for the process lifetime.
 *
 * Reference counting happens under [registryMutex], so an entry cannot be removed while another
 * caller is acquiring or using it. The final caller removes the idle mutex in `finally`.
 */
class KeyedMutexRegistry<K> {
    private data class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val registryMutex = Mutex()
    private val entries = mutableMapOf<K, Entry>()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val entry = registryMutex.withLock {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            registryMutex.withLock {
                entry.users--
                if (entry.users == 0 && !entry.mutex.isLocked) entries.remove(key, entry)
            }
        }
    }

    suspend fun forget(key: K) {
        registryMutex.withLock {
            entries[key]?.takeIf { it.users == 0 && !it.mutex.isLocked }?.let { entries.remove(key, it) }
        }
    }

    internal suspend fun sizeForTests(): Int = registryMutex.withLock { entries.size }
}
