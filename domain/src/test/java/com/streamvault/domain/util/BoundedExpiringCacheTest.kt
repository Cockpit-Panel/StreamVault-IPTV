package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BoundedExpiringCacheTest {
    @Test
    fun oneHundredThousandUniqueKeysRemainWithinFixedBound() {
        val cache = BoundedExpiringCache<Int, Int>(maxEntries = 128, ttlMillis = Long.MAX_VALUE)

        repeat(100_000) { cache.put(it, it) }

        assertThat(cache.size()).isEqualTo(128)
        assertThat(cache.get(99_999)).isEqualTo(99_999)
    }

    @Test
    fun proactiveSweepExpiresEntriesWithoutSameKeyLookup() {
        var now = 0L
        val cache = BoundedExpiringCache<Int, Int>(8, 100L) { now }
        cache.put(1, 1)

        now = 101L
        cache.sweep()

        assertThat(cache.size()).isEqualTo(0)
    }

    @Test
    fun evictedResourceRemainsUsableByActiveOwner() {
        data class Resource(val id: Int, var active: Boolean = true)
        val cache = BoundedExpiringCache<Int, Resource>(1, Long.MAX_VALUE)
        val activeCallResource = Resource(1)
        cache.put(1, activeCallResource)

        cache.put(2, Resource(2))

        assertThat(cache.get(1)).isNull()
        assertThat(activeCallResource.active).isTrue()
        assertThat(activeCallResource.id).isEqualTo(1)
    }
}
