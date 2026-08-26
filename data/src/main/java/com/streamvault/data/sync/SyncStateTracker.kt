package com.streamvault.data.sync

import com.streamvault.domain.model.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicLong

/** Identifies one provider sync run for status publication. */
internal data class SyncStateSession(val providerId: Long, val epoch: Long)

internal class SyncStateTracker {
    private data class ProviderStateSnapshot(
        val state: SyncState,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(Dispatchers.Default)
    private val stateMachine = SyncStateMachine()
    private val nextEpoch = AtomicLong(0L)
    private val lock = Any()
    private val activeSessions = mutableMapOf<Long, SyncStateSession>()
    private val providerSnapshots = MutableStateFlow<Map<Long, ProviderStateSnapshot>>(emptyMap())

    val statesByProvider: StateFlow<Map<Long, SyncState>> = providerSnapshots
        .map { snapshots -> snapshots.mapValues { it.value.state } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val aggregateState: StateFlow<SyncState> = providerSnapshots
        .map { snapshots -> aggregate(snapshots) }
        .stateIn(scope, SharingStarted.Eagerly, SyncState.Idle)

    fun current(providerId: Long): SyncState = providerSnapshots.value[providerId]?.state ?: SyncState.Idle

    fun begin(providerId: Long): SyncStateSession {
        val session = SyncStateSession(providerId, nextEpoch.incrementAndGet())
        synchronized(lock) {
            activeSessions[providerId] = session
        }
        return session
    }

    /** Returns false when a stale run attempts to publish after replacement. */
    fun publish(session: SyncStateSession, state: SyncState): Boolean {
        synchronized(lock) {
            if (activeSessions[session.providerId] != session) return false
            providerSnapshots.update { snapshots ->
                val current = snapshots[session.providerId]?.state ?: SyncState.Idle
                snapshots + (
                    session.providerId to ProviderStateSnapshot(
                        stateMachine.transition(current, state)
                    )
                )
            }
            return true
        }
    }

    fun finish(session: SyncStateSession) {
        synchronized(lock) {
            activeSessions.remove(session.providerId, session)
        }
    }

    fun reset(providerId: Long? = null) {
        synchronized(lock) {
            if (providerId == null) {
                activeSessions.clear()
            } else {
                activeSessions.remove(providerId)
            }
            providerSnapshots.update { snapshots ->
                if (providerId == null) emptyMap() else snapshots - providerId
            }
        }
    }

    private fun aggregate(snapshots: Map<Long, ProviderStateSnapshot>): SyncState {
        if (snapshots.isEmpty()) {
            return SyncState.Idle
        }
        val values = snapshots.values.toList()
        val syncing = values.filter { it.state is SyncState.Syncing }
        val errors = values.filter { it.state is SyncState.Error }
        val partials = values.filter { it.state is SyncState.Partial }
        if (syncing.isNotEmpty()) {
            val representative = syncing.maxByOrNull { it.updatedAt }?.state as? SyncState.Syncing
            val phaseSuffix = representative?.phase?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val errorSuffix = errors.takeIf { it.isNotEmpty() }?.let { " · ${it.size} failing" }.orEmpty()
            val partialSuffix = partials.takeIf { it.isNotEmpty() }?.let { " · ${it.size} partial" }.orEmpty()
            return SyncState.Syncing("Syncing ${syncing.size} provider${if (syncing.size == 1) "" else "s"}$phaseSuffix$errorSuffix$partialSuffix")
        }
        if (errors.isNotEmpty()) {
            if (errors.size == 1 && partials.isEmpty()) {
                return errors.single().state
            }
            val latestError = errors.maxByOrNull { it.updatedAt }?.state as? SyncState.Error
            val summary = buildString {
                append("${errors.size} provider sync")
                append(if (errors.size == 1) " failed" else "s failed")
                if (partials.isNotEmpty()) {
                    append(" · ${partials.size} partial")
                }
            }
            return SyncState.Error(summary, latestError?.cause)
        }
        if (partials.isNotEmpty()) {
            if (partials.size == 1) {
                return partials.single().state
            }
            val warnings = partials
                .asSequence()
                .map { it.state as SyncState.Partial }
                .flatMap { it.warnings.asSequence() }
                .distinct()
                .toList()
            return SyncState.Partial(
                message = "${partials.size} provider syncs completed with warnings",
                warnings = warnings,
                timestamp = partials.maxOf { (it.state as SyncState.Partial).timestamp }
            )
        }
        return values.maxByOrNull { it.updatedAt }?.state ?: SyncState.Idle
    }
}
