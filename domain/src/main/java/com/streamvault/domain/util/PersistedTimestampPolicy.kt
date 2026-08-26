package com.streamvault.domain.util

/**
 * Freshness policy for wall-clock timestamps that survive process death.
 *
 * A missing, non-positive, or future timestamp cannot prove that work is still live. Treating
 * those values as stale prevents a manual or NTP clock correction from suppressing recovery
 * until wall time catches up. In-process elapsed durations should use a monotonic clock instead.
 */
object PersistedTimestampPolicy {
    fun isFresh(
        timestampMillis: Long?,
        nowMillis: Long,
        freshForMillis: Long
    ): Boolean {
        if (timestampMillis == null || timestampMillis <= 0L || freshForMillis <= 0L) {
            return false
        }
        if (timestampMillis > nowMillis) return false
        return nowMillis - timestampMillis < freshForMillis
    }
}
