package com.streamvault.domain.provider

import com.streamvault.domain.model.ProviderType

/** Stable identity of an external source, independent of labels, URLs, and imported provider IDs. */
data class PluginSourceIdentity(
    val packageName: String,
    val serviceClassName: String,
    val manifestId: String
)

sealed interface ProviderSource {
    val providerId: Long
    val enabled: Boolean
    val capabilities: Set<String>
}

data class NativeProviderSource(
    override val providerId: Long,
    override val enabled: Boolean,
    override val capabilities: Set<String>,
    val providerType: ProviderType
) : ProviderSource

/**
 * First-class plugin source. [backingProviderType] describes storage/import representation only;
 * lifecycle and capability ownership remain attached to [identity].
 */
data class PluginProviderSource(
    override val providerId: Long,
    override val enabled: Boolean,
    override val capabilities: Set<String>,
    val identity: PluginSourceIdentity,
    val backingProviderType: ProviderType
) : ProviderSource

interface ProviderSourceRegistry {
    suspend fun sources(): List<ProviderSource>
}
