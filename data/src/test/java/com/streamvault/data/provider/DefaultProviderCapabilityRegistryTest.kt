package com.streamvault.data.provider

import com.streamvault.domain.model.Provider as StableProvider

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import com.streamvault.domain.provider.*
import org.junit.Test

class DefaultProviderCapabilityRegistryTest {
    @Test(expected = IllegalArgumentException::class)
    fun `registry rejects missing provider types`() {
        DefaultProviderCapabilityRegistry(listOf(factory(ProviderType.M3U)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `registry rejects duplicate provider types`() {
        DefaultProviderCapabilityRegistry(
            ProviderType.entries.map(::factory) + factory(ProviderType.M3U)
        )
    }

    @Test
    fun `registry resolves exactly one factory for every provider type`() {
        val registry = DefaultProviderCapabilityRegistry(ProviderType.entries.map(::factory))
        ProviderType.entries.forEach { type ->
            val snapshot = snapshot(type)
            assertThat(registry.resolve(snapshot)).isInstanceOf(CapabilityResolution.Available::class.java)
        }
    }

    @Test
    fun `factory configuration failure is typed`() {
        val factories = ProviderType.entries.map { type ->
            if (type == ProviderType.M3U) object : ProviderCapabilityFactory {
                override val providerType = type
                override fun create(snapshot: ProviderSnapshot): ProviderCapabilitySet =
                    throw IllegalArgumentException("bad config")
            } else factory(type)
        }
        assertThat(DefaultProviderCapabilityRegistry(factories).resolve(snapshot(ProviderType.M3U)))
            .isEqualTo(CapabilityResolution.ConfigurationError("bad config"))
    }

    private fun factory(type: ProviderType) = object : ProviderCapabilityFactory {
        override val providerType = type
        override fun create(snapshot: ProviderSnapshot): ProviderCapabilitySet = EmptySet(snapshot)
    }

    private fun snapshot(type: ProviderType): ProviderSnapshot {
        val config: ProviderConfiguration = when (type) {
            ProviderType.XTREAM_CODES -> XtreamConfig("https://x.test", "u", "p")
            ProviderType.M3U -> M3uConfig("https://m.test/list.m3u")
            ProviderType.STALKER_PORTAL -> StalkerConfig("https://s.test", StalkerDeviceIdentity("00:11:22:33:44:55"))
            ProviderType.JELLYFIN -> JellyfinConfig("https://j.test", "u", "t")
        }
        return ProviderSnapshot(
            provider = StableProvider(name = type.name, type = type),
            configuration = config,
            configurationGeneration = 1
        )
    }

    private class EmptySet(override val snapshot: ProviderSnapshot) : ProviderCapabilitySet {
        private fun unsupported() = CapabilityResolution.Unsupported("test")
        override fun authentication(): CapabilityResolution<ProviderAuthenticator> = unsupported()
        override fun liveCatalog(): CapabilityResolution<LiveCatalogSource> = unsupported()
        override fun vodCatalog(): CapabilityResolution<VodCatalogSource> = unsupported()
        override fun seriesCatalog(): CapabilityResolution<SeriesCatalogSource> = unsupported()
        override fun guide(): CapabilityResolution<GuideSource> = unsupported()
        override fun playback(): CapabilityResolution<PlaybackResolver> = unsupported()
        override fun catchUp(): CapabilityResolution<CatchUpSource> = unsupported()
    }
}
