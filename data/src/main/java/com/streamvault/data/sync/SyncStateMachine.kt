package com.streamvault.data.sync

import com.streamvault.domain.model.SyncState

/**
 * The single transition policy for provider sync status.
 *
 * A terminal status can only be replaced by a new run (Syncing), reset (Idle),
 * or another value of the same terminal kind. This prevents a stale callback
 * from silently changing a completed run into a different terminal outcome.
 */
internal class SyncStateMachine {
    fun transition(current: SyncState, requested: SyncState): SyncState {
        if (current is SyncState.Idle || current is SyncState.Syncing) {
            return requested
        }
        if (requested is SyncState.Idle || requested is SyncState.Syncing) {
            return requested
        }
        require(current::class == requested::class) {
            "Invalid sync state transition: ${current::class.simpleName} -> ${requested::class.simpleName}"
        }
        return requested
    }
}
