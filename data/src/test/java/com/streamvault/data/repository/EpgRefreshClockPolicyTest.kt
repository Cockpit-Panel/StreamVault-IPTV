package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgRefreshClockPolicyTest {

    @Test
    fun `future persisted refresh does not rate limit after backward clock jump`() {
        assertThat(
            shouldRateLimitEpgRefresh(
                lastSuccessfulRefreshAt = 10_001L,
                now = 10_000L,
                minimumIntervalMillis = 300_000L
            )
        ).isFalse()
    }

    @Test
    fun `recent persisted refresh still rate limits duplicate owner`() {
        assertThat(
            shouldRateLimitEpgRefresh(
                lastSuccessfulRefreshAt = 10_000L,
                now = 10_001L,
                minimumIntervalMillis = 300_000L
            )
        ).isTrue()
    }
}
