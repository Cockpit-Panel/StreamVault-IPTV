package com.streamvault.domain.provider

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ProviderType
import org.junit.Test

class ProviderCapabilityMatrixTest {
    @Test
    fun `capability matrix is exact for every provider type`() {
        assertThat(ProviderCapabilityMatrix.potentialCapabilities(ProviderType.XTREAM_CODES))
            .containsExactlyElementsIn(ProviderCapability.entries)
        assertThat(ProviderCapabilityMatrix.potentialCapabilities(ProviderType.STALKER_PORTAL))
            .containsExactlyElementsIn(ProviderCapability.entries)
        assertThat(ProviderCapabilityMatrix.potentialCapabilities(ProviderType.M3U)).containsExactly(
            ProviderCapability.LIVE_CATALOG,
            ProviderCapability.VOD_CATALOG,
            ProviderCapability.GUIDE,
            ProviderCapability.PLAYBACK,
            ProviderCapability.CATCH_UP
        )
        assertThat(ProviderCapabilityMatrix.potentialCapabilities(ProviderType.JELLYFIN)).containsExactly(
            ProviderCapability.AUTHENTICATION,
            ProviderCapability.VOD_CATALOG,
            ProviderCapability.SERIES_CATALOG,
            ProviderCapability.PLAYBACK
        )
    }
}
