package com.streamvault.data.provider

import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.ProviderCapabilityRegistry
import com.streamvault.domain.provider.ProviderCapabilitySet
import com.streamvault.domain.repository.ProviderSnapshotRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Loads one immutable snapshot before selecting any provider execution capability. */
@Singleton
class ProviderCapabilityResolver @Inject constructor(
    private val snapshots: ProviderSnapshotRepository,
    private val registry: ProviderCapabilityRegistry
) {
    suspend fun snapshot(providerId: Long) = snapshots.getSnapshot(providerId)

    suspend fun resolve(providerId: Long): CapabilityResolution<ProviderCapabilitySet> {
        val snapshot = snapshots.getSnapshot(providerId)
            ?: return CapabilityResolution.ConfigurationError("Provider $providerId has no typed configuration")
        return registry.resolve(snapshot)
    }
}
