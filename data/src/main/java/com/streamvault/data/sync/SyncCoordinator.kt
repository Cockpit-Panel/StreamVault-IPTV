package com.streamvault.data.sync

import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.provider.CapabilityResolution

/**
 * Provider-neutral orchestration boundary for catalog and guide work.
 *
 * Provider-specific policy lives behind [CatalogSyncPlan] implementations. This class owns
 * resolution and keeps callers from reintroducing provider-type switches for full sync, section
 * repair, or guide refresh.
 */
internal class SyncCoordinator(
    private val plans: CatalogSyncPlanRegistry,
    private val continuationScheduler: SyncContinuationScheduler = SyncContinuationScheduler.NONE
) {
    suspend fun syncFull(request: FullProviderSyncRequest): CapabilityResolution<SyncOutcome> =
        resolve(request.snapshot).map {
            handOffContinuations(request.snapshot, SyncActivationPolicy.validate(it.syncFull(request)))
        }

    suspend fun syncSection(
        request: SectionProviderSyncRequest
    ): CapabilityResolution<SyncOutcome> = resolve(request.snapshot)
        .flatMap { it.syncSection(request) }
        .validateOutcome(request.snapshot)

    suspend fun syncGuide(
        request: ProviderGuideSyncRequest
    ): CapabilityResolution<ProviderGuideSyncResult> = resolve(request.snapshot).flatMap { it.syncGuide(request) }

    fun resolve(snapshot: ProviderSnapshot): CapabilityResolution<CatalogSyncPlan> = plans.resolve(snapshot)

    private suspend fun <T, R> CapabilityResolution<T>.flatMap(
        block: suspend (T) -> CapabilityResolution<R>
    ): CapabilityResolution<R> = when (this) {
        is CapabilityResolution.Available -> block(capability)
        is CapabilityResolution.ConfigurationError -> this
        is CapabilityResolution.Restricted -> this
        is CapabilityResolution.Unsupported -> this
    }

    private suspend fun <T, R> CapabilityResolution<T>.map(
        block: suspend (T) -> R
    ): CapabilityResolution<R> = when (this) {
        is CapabilityResolution.Available -> CapabilityResolution.Available(block(capability))
        is CapabilityResolution.ConfigurationError -> this
        is CapabilityResolution.Restricted -> this
        is CapabilityResolution.Unsupported -> this
        }

    private suspend fun CapabilityResolution<SyncOutcome>.validateOutcome(
        snapshot: ProviderSnapshot
    ): CapabilityResolution<SyncOutcome> =
        when (this) {
            is CapabilityResolution.Available -> CapabilityResolution.Available(
                handOffContinuations(snapshot, SyncActivationPolicy.validate(capability))
            )
            is CapabilityResolution.ConfigurationError -> this
            is CapabilityResolution.Restricted -> this
            is CapabilityResolution.Unsupported -> this
        }

    private suspend fun handOffContinuations(
        snapshot: ProviderSnapshot,
        outcome: SyncOutcome
    ): SyncOutcome {
        if (outcome.continuationWork.isNotEmpty()) {
            continuationScheduler.schedule(snapshot, outcome.continuationWork)
        }
        return outcome
    }
}

internal fun interface SyncContinuationScheduler {
    suspend fun schedule(snapshot: ProviderSnapshot, work: List<SyncContinuation>)

    companion object {
        val NONE = SyncContinuationScheduler { _, _ -> }
    }
}
