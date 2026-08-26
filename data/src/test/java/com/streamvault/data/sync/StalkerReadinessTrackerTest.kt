package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StalkerReadiness
import org.junit.Test

class StalkerReadinessTrackerTest {
    @Test
    fun `readiness transitions publish committed milestone timestamps in order`() {
        val tracker = StalkerReadinessTracker()

        tracker.start(providerId = 7L, now = 100L)
        tracker.authenticated(providerId = 7L, now = 200L)
        tracker.liveReady(providerId = 7L, now = 300L)
        tracker.categoriesReady(providerId = 7L, now = 400L)
        tracker.ready(providerId = 7L, warningCount = 0, now = 500L)

        assertThat(tracker.current(7L)).isEqualTo(
            com.streamvault.domain.model.StalkerReadinessSnapshot(
                providerId = 7L,
                state = StalkerReadiness.READY,
                syncStartedAt = 100L,
                authenticatedAt = 200L,
                liveReadyAt = 300L,
                categoriesReadyAt = 400L,
                readyAt = 500L,
                warningCount = 0
            )
        )
    }

    @Test
    fun `warnings produce ready with warnings and clear removes provider`() {
        val tracker = StalkerReadinessTracker()
        tracker.start(9L, 100L)
        tracker.ready(9L, warningCount = 2, now = 200L)

        assertThat(tracker.current(9L)?.state).isEqualTo(StalkerReadiness.READY_WITH_WARNINGS)
        assertThat(tracker.current(9L)?.warningCount).isEqualTo(2)

        tracker.clear(9L)
        assertThat(tracker.current(9L)).isNull()
    }
}
