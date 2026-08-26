package com.streamvault.domain.model

import com.streamvault.domain.model.Provider as StableProvider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderSnapshotTest {
    @Test(expected = IllegalArgumentException::class)
    fun `snapshot rejects mismatched configuration`() {
        ProviderSnapshot(
            provider = StableProvider(name = "p", type = ProviderType.M3U),
            configuration = JellyfinConfig("https://media.test", "u", "token"),
            configurationGeneration = 1
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `learning rejects stale observations`() {
        StalkerPortalLearning(
            configurationGeneration = 2,
            profileId = StalkerObservation(
                value = "profile",
                configurationGeneration = 1,
                source = StalkerObservationSource.DISCOVERY,
                observedAt = 10
            )
        )
    }

    @Test
    fun `typed configuration exposes its fixed provider type`() {
        assertThat(XtreamConfig("https://x.test", "u", "p").type).isEqualTo(ProviderType.XTREAM_CODES)
        assertThat(M3uConfig("https://m.test/list.m3u").type).isEqualTo(ProviderType.M3U)
        assertThat(StalkerConfig("https://s.test", StalkerDeviceIdentity("00:11:22:33:44:55")).type)
            .isEqualTo(ProviderType.STALKER_PORTAL)
        assertThat(JellyfinConfig("https://j.test", "u", "t").type).isEqualTo(ProviderType.JELLYFIN)
    }
}
