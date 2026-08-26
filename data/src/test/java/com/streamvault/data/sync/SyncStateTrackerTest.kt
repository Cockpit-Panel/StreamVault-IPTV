package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.SyncState
import org.junit.Test

class SyncStateTrackerTest {
    @Test
    fun `stale session cannot publish over replacement run`() {
        val tracker = SyncStateTracker()
        val first = tracker.begin(7L)
        tracker.publish(first, SyncState.Syncing("first"))

        val replacement = tracker.begin(7L)
        tracker.publish(replacement, SyncState.Syncing("replacement"))

        assertThat(tracker.publish(first, SyncState.Error("stale completion"))).isFalse()
        assertThat(tracker.current(7L)).isEqualTo(SyncState.Syncing("replacement"))
    }

    @Test
    fun `terminal error can be replaced only by a new run`() {
        val tracker = SyncStateTracker()
        val first = tracker.begin(11L)
        tracker.publish(first, SyncState.Syncing("running"))
        tracker.publish(first, SyncState.Error("failed"))

        val replacement = tracker.begin(11L)
        assertThat(tracker.publish(replacement, SyncState.Syncing("retry"))).isTrue()
        assertThat(tracker.current(11L)).isEqualTo(SyncState.Syncing("retry"))
    }
}
