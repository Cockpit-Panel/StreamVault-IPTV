package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.SyncState
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncStateMachineTest {
    private val machine = SyncStateMachine()

    @Test
    fun `new run may move from any terminal state to syncing`() {
        assertThat(machine.transition(SyncState.Success(1L), SyncState.Syncing("retry")))
            .isEqualTo(SyncState.Syncing("retry"))
        assertThat(machine.transition(SyncState.Partial("partial"), SyncState.Syncing("retry")))
            .isEqualTo(SyncState.Syncing("retry"))
        assertThat(machine.transition(SyncState.Error("failed"), SyncState.Syncing("retry")))
            .isEqualTo(SyncState.Syncing("retry"))
    }

    @Test
    fun `terminal result cannot be replaced by a different terminal result`() {
        assertThrows(IllegalArgumentException::class.java) {
            machine.transition(SyncState.Success(1L), SyncState.Error("stale callback"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            machine.transition(SyncState.Partial("partial"), SyncState.Success(2L))
        }
    }

    @Test
    fun `reset and same-kind updates remain valid`() {
        val partial = SyncState.Partial("partial", warnings = listOf("warning"))
        assertThat(machine.transition(partial, SyncState.Partial("new partial")))
            .isEqualTo(SyncState.Partial("new partial"))
        assertThat(machine.transition(partial, SyncState.Idle)).isEqualTo(SyncState.Idle)
    }
}
