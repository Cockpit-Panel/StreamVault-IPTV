package com.streamvault.domain.util

import java.util.LinkedHashMap

/**
 * Small process-local cache with a hard entry bound and lazy time-based expiry.
 *
 * Eviction only drops the cache reference. Callers that cache resources such as HTTP clients
 * must not close them from the eviction callback because an in-flight operation may still own
 * the resource.
 */
class BoundedExpiringCache<K, V>(
    private val maxEntries: Int,
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry<V>(val value: V, val storedAt: Long)

    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var operationsSinceSweep = 0

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMillis >= 0L) { "ttlMillis must not be negative" }
    }

    @Synchronized
    fun get(key: K): V? {
        sweepIfDue()
        val entry = entries[key] ?: return null
        if (isExpired(entry)) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V): V? {
        sweepIfDue()
        val previous = entries.put(key, Entry(value, clock()))?.value
        trimToBound()
        return previous
    }

    @Synchronized
    fun getOrPut(key: K, factory: () -> V): V {
        get(key)?.let { return it }
        val value = factory()
        put(key, value)
        return value
    }

    @Synchronized
    fun remove(key: K): V? = entries.remove(key)?.value

    @Synchronized
    fun removeIf(predicate: (K, V) -> Boolean): Int {
        var removed = 0
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (predicate(key, entry.value)) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    /** Proactively expires stale entries without requiring a lookup of the same key. */
    @Synchronized
    fun sweep() = sweepAll()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int {
        sweepAll()
        return entries.size
    }

    private fun isExpired(entry: Entry<V>): Boolean =
        ttlMillis == 0L || clock() - entry.storedAt >= ttlMillis

    private fun sweepIfDue() {
        operationsSinceSweep++
        if (operationsSinceSweep >= SWEEP_INTERVAL) {
            operationsSinceSweep = 0
            sweepAll()
        }
    }

    private fun sweepAll() {
        if (entries.isEmpty()) return
        val now = clock()
        entries.entries.removeIf { (_, entry) -> ttlMillis == 0L || now - entry.storedAt >= ttlMillis }
    }

    private fun trimToBound() {
        while (entries.size > maxEntries) {
            entries.entries.iterator().apply {
                if (hasNext()) {
                    next()
                    remove()
                }
            }
        }
    }

    private companion object {
        const val SWEEP_INTERVAL = 128
    }
}
