package com.streamvault.data.sync

import com.streamvault.domain.sync.SyncProgress
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Identifies one run of a provider sync. */
data class SyncProgressSession(val providerId: Long, val epoch: Long)

data class ProviderSyncProgress(
    val session: SyncProgressSession,
    val progress: SyncProgress,
    val updatedAt: Long = System.currentTimeMillis()
)

/** A presentation-oriented summary; it is deliberately derived from keyed state. */
data class SyncProgressAggregate(
    val activeProviderCount: Int,
    val representative: ProviderSyncProgress
)

/**
 * Process-local catalog-sync progress keyed by provider and monotonically increasing session epoch.
 * A completion or late update only affects the session that created it; it cannot clear progress
 * published by another provider.
 */
@Singleton
class SyncProgressBus @Inject constructor() {
    private val nextEpoch = AtomicLong(0)
    private val lock = Any()
    private val activeSessions = mutableMapOf<Long, SyncProgressSession>()
    private val _progressByProvider = MutableStateFlow<Map<Long, ProviderSyncProgress>>(emptyMap())
    private val _aggregate = MutableStateFlow<SyncProgressAggregate?>(null)

    val progressByProvider: StateFlow<Map<Long, ProviderSyncProgress>> = _progressByProvider.asStateFlow()

    val aggregate: StateFlow<SyncProgressAggregate?> = _aggregate.asStateFlow()

    fun begin(providerId: Long): SyncProgressSession {
        val session = SyncProgressSession(providerId, nextEpoch.incrementAndGet())
        synchronized(lock) {
            activeSessions[providerId] = session
            // A new session replaces only this provider's prior presentation state.
            _progressByProvider.value = _progressByProvider.value - providerId
            publishAggregate()
        }
        return session
    }

    fun emit(session: SyncProgressSession, progress: SyncProgress) {
        synchronized(lock) {
            if (activeSessions[session.providerId] != session) return
            _progressByProvider.value = _progressByProvider.value + (
                session.providerId to ProviderSyncProgress(session, progress)
            )
            publishAggregate()
        }
    }

    fun finish(session: SyncProgressSession) {
        synchronized(lock) {
            if (activeSessions[session.providerId] != session) return
            activeSessions.remove(session.providerId)
            _progressByProvider.value = _progressByProvider.value - session.providerId
            publishAggregate()
        }
    }

    private fun publishAggregate() {
        val active = _progressByProvider.value
        _aggregate.value = active.values.maxWithOrNull(
            compareBy<ProviderSyncProgress> { it.updatedAt }.thenBy { it.session.epoch }
        )?.let { representative ->
            SyncProgressAggregate(active.size, representative)
        }
    }
}
