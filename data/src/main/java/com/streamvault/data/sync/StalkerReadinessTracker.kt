package com.streamvault.data.sync

import com.streamvault.data.remote.stalker.StalkerTelemetry
import com.streamvault.domain.model.StalkerReadiness
import com.streamvault.domain.model.StalkerReadinessSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory lifecycle state for the current or most recent Stalker readiness run. */
@Singleton
class StalkerReadinessTracker @Inject constructor() {
    private val snapshots = MutableStateFlow<Map<Long, StalkerReadinessSnapshot>>(emptyMap())

    val all = snapshots.asStateFlow()

    fun current(providerId: Long): StalkerReadinessSnapshot? = snapshots.value[providerId]

    fun observe(providerId: Long): Flow<StalkerReadinessSnapshot?> =
        snapshots.map { states -> states[providerId] }

    fun start(providerId: Long, now: Long = System.currentTimeMillis()) {
        publish(
            StalkerReadinessSnapshot(
                providerId = providerId,
                state = StalkerReadiness.AUTHENTICATING,
                syncStartedAt = now
            )
        )
    }

    fun authenticated(providerId: Long, now: Long = System.currentTimeMillis()) {
        mutate(providerId) { snapshot -> snapshot.copy(authenticatedAt = now) }
    }

    fun liveReady(providerId: Long, now: Long = System.currentTimeMillis()) {
        mutate(providerId) { snapshot ->
            snapshot.copy(state = StalkerReadiness.LIVE_READY, liveReadyAt = now)
        }
    }

    fun categoriesReady(providerId: Long, now: Long = System.currentTimeMillis()) {
        mutate(providerId) { snapshot ->
            snapshot.copy(state = StalkerReadiness.CATEGORIES_READY, categoriesReadyAt = now)
        }
    }

    fun ready(providerId: Long, warningCount: Int, now: Long = System.currentTimeMillis()) {
        mutate(providerId) { snapshot ->
            snapshot.copy(
                state = if (warningCount > 0) {
                    StalkerReadiness.READY_WITH_WARNINGS
                } else {
                    StalkerReadiness.READY
                },
                readyAt = now,
                warningCount = warningCount.coerceAtLeast(0)
            )
        }
    }

    fun clear(providerId: Long) {
        snapshots.update { current -> current - providerId }
    }

    private fun mutate(
        providerId: Long,
        transform: (StalkerReadinessSnapshot) -> StalkerReadinessSnapshot
    ) {
        snapshots.update { current ->
            val snapshot = current[providerId] ?: return@update current
            current + (providerId to transform(snapshot))
        }
        snapshots.value[providerId]?.let(::emitTelemetry)
    }

    private fun publish(snapshot: StalkerReadinessSnapshot) {
        snapshots.update { current -> current + (snapshot.providerId to snapshot) }
        emitTelemetry(snapshot)
    }

    private fun emitTelemetry(snapshot: StalkerReadinessSnapshot) {
        StalkerTelemetry.readinessMilestone(snapshot)
    }
}
