package com.streamvault.data.provider

import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.PlaybackResolver
import com.streamvault.domain.util.BoundedExpiringCache
import javax.inject.Inject
import javax.inject.Singleton

/** Temporary ARCH-004 compatibility boundary for Stalker playback client lifetime. */
@Singleton
class StalkerPlaybackCapabilityCache @Inject constructor(
    private val clients: TypedProviderClientFactory
) {
    private data class Entry(
        val generation: Long,
        val resolver: PlaybackResolver
    )

    private val entries = BoundedExpiringCache<Long, Entry>(
        maxEntries = MAX_ENTRIES,
        ttlMillis = ENTRY_TTL_MILLIS
    )

    fun resolve(snapshot: ProviderSnapshot): CapabilityResolution<PlaybackResolver> {
        entries.get(snapshot.provider.id)
            ?.takeIf { it.generation == snapshot.configurationGeneration }
            ?.let { return CapabilityResolution.Available(it.resolver) }
        entries.remove(snapshot.provider.id)
        val resolver = when (val result = clients.stalker(snapshot)) {
            is CapabilityResolution.Available -> result.capability
            is CapabilityResolution.ConfigurationError -> return result
            is CapabilityResolution.Restricted -> return result
            is CapabilityResolution.Unsupported -> return result
        }
        entries.put(snapshot.provider.id, Entry(snapshot.configurationGeneration, resolver))
        return CapabilityResolution.Available(resolver)
    }

    fun invalidate(providerId: Long) {
        entries.remove(providerId)
    }

    fun invalidateAll() = entries.clear()

    internal fun sizeForTests(): Int = entries.size()

    private companion object {
        const val MAX_ENTRIES = 32
        const val ENTRY_TTL_MILLIS = 30L * 60L * 1_000L
    }
}
