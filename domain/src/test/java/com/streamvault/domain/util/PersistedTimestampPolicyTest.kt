package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PersistedTimestampPolicyTest {

    @Test
    fun `future timestamp after backward clock jump is stale`() {
        assertThat(
            PersistedTimestampPolicy.isFresh(
                timestampMillis = 10_001L,
                nowMillis = 10_000L,
                freshForMillis = 1_000L
            )
        ).isFalse()
    }

    @Test
    fun `forward clock jump beyond window is stale`() {
        assertThat(
            PersistedTimestampPolicy.isFresh(
                timestampMillis = 10_000L,
                nowMillis = 20_000L,
                freshForMillis = 1_000L
            )
        ).isFalse()
    }

    @Test
    fun `exact freshness threshold is stale`() {
        assertThat(
            PersistedTimestampPolicy.isFresh(
                timestampMillis = 10_000L,
                nowMillis = 11_000L,
                freshForMillis = 1_000L
            )
        ).isFalse()
    }

    @Test
    fun `zero negative and missing timestamps are stale`() {
        listOf<Long?>(null, 0L, -1L).forEach { timestamp ->
            assertThat(
                PersistedTimestampPolicy.isFresh(
                    timestampMillis = timestamp,
                    nowMillis = 10_000L,
                    freshForMillis = 1_000L
                )
            ).isFalse()
        }
    }

    @Test
    fun `positive timestamp inside window is fresh`() {
        assertThat(
            PersistedTimestampPolicy.isFresh(
                timestampMillis = 10_000L,
                nowMillis = 10_999L,
                freshForMillis = 1_000L
            )
        ).isTrue()
    }
}
