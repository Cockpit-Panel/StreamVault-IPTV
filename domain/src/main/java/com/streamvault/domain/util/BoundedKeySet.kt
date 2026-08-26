package com.streamvault.domain.util

import kotlinx.coroutines.delay

/** Atomic admission set for in-flight work; rejects new unique keys at a fixed capacity. */
class BoundedKeySet<K>(private val maxEntries: Int) {
    private val entries = LinkedHashSet<K>()

    init { require(maxEntries > 0) }

    @Synchronized
    fun tryAdd(key: K): Boolean {
        if (key in entries || entries.size >= maxEntries) return false
        entries.add(key)
        return true
    }

    /**
     * Waits for an admission slot instead of silently dropping work when the bound is full.
     * A duplicate key is still coalesced and returns false immediately.
     */
    suspend fun awaitAdd(key: K, retryDelayMs: Long = 50L): Boolean {
        while (true) {
            if (tryAdd(key)) return true
            if (contains(key)) return false
            delay(retryDelayMs)
        }
    }

    @Synchronized fun remove(key: K): Boolean = entries.remove(key)
    @Synchronized fun size(): Int = entries.size
    @Synchronized fun contains(key: K): Boolean = key in entries
}
