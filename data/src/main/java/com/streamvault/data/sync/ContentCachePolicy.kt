package com.streamvault.data.sync

import com.streamvault.domain.util.PersistedTimestampPolicy

object ContentCachePolicy {
    const val CATALOG_TTL_MILLIS = 24L * 60 * 60 * 1000L
    const val SERIES_CATEGORY_TTL_MILLIS = 6L * 60 * 60 * 1000L
    const val EPG_TTL_MILLIS = 6L * 60 * 60 * 1000L

    fun shouldRefresh(lastSyncAt: Long, ttlMillis: Long, now: Long = System.currentTimeMillis()): Boolean =
        !PersistedTimestampPolicy.isFresh(lastSyncAt, now, ttlMillis)
}
