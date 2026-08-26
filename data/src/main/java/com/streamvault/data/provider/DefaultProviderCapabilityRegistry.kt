package com.streamvault.data.provider

import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.ProviderCapabilityFactory
import com.streamvault.domain.provider.ProviderCapabilityRegistry
import com.streamvault.domain.provider.ProviderCapabilitySet

/** Immutable registry: invalid factory topology fails at composition time, not mid-sync. */
class DefaultProviderCapabilityRegistry(
    factories: Collection<ProviderCapabilityFactory>
) : ProviderCapabilityRegistry {
    private val factoriesByType: Map<ProviderType, ProviderCapabilityFactory>

    init {
        val duplicates = factories.groupingBy { it.providerType }.eachCount().filterValues { it != 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate provider capability factories: $duplicates" }
        val registered = factories.mapTo(mutableSetOf()) { it.providerType }
        val missing = ProviderType.entries.toSet() - registered
        require(missing.isEmpty()) { "Missing provider capability factories: $missing" }
        factoriesByType = factories.associateBy { it.providerType }
    }

    override fun resolve(snapshot: ProviderSnapshot): CapabilityResolution<ProviderCapabilitySet> {
        if (snapshot.provider.type != snapshot.configuration.type) {
            return CapabilityResolution.ConfigurationError(
                "Provider type ${snapshot.provider.type} does not match ${snapshot.configuration.type} configuration"
            )
        }
        val factory = factoriesByType.getValue(snapshot.provider.type)
        if (factory.providerType != snapshot.configuration.type) {
            return CapabilityResolution.ConfigurationError("Factory/configuration type mismatch")
        }
        return try {
            CapabilityResolution.Available(factory.create(snapshot))
        } catch (error: IllegalArgumentException) {
            CapabilityResolution.ConfigurationError(error.message ?: "Invalid provider configuration")
        }
    }
}
