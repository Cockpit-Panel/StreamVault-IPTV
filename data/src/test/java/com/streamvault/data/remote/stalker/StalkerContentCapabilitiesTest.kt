package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StalkerContentCapabilitiesTest {
    @Test
    fun codec_preservesLegacyFlatCapabilities_andRoundTripsVersionedContentCapabilities() {
        val legacy = """{"LIVE":"SUPPORTED","VOD":"SUPPORTED","SERIES":"EMPTY"}"""
        val capabilities = StalkerContentCapabilities(
            configurationGeneration = 42,
            seriesDetailDialect = StalkerSeriesDetailDialect.VOD_SEASON_SHELLS,
            episodeSelectorDialect = StalkerEpisodeSelectorDialect.PARENT_COMMAND_WITH_SERIES_NUMBER,
            vodPlaybackDialect = StalkerVodPlaybackDialect.CREATE_LINK,
            validationEvidence = listOf("series_details_non_empty:vod_derived")
        )

        val encoded = StalkerContentCapabilitiesCodec.encode(legacy, capabilities)
        val decoded = StalkerContentCapabilitiesCodec.decode(encoded, 42)

        assertThat(encoded).contains("\"LIVE\":\"SUPPORTED\"")
        assertThat(decoded).isEqualTo(capabilities)
    }

    @Test
    fun codec_invalidatesLearnedDialects_whenConfigurationGenerationChanges() {
        val encoded = StalkerContentCapabilitiesCodec.encode(
            null,
            StalkerContentCapabilities(
                configurationGeneration = 7,
                seriesDetailDialect = StalkerSeriesDetailDialect.VOD_SEASON_SHELLS
            )
        )

        val decoded = StalkerContentCapabilitiesCodec.decode(encoded, 8)

        assertThat(decoded.configurationGeneration).isEqualTo(8)
        assertThat(decoded.seriesDetailDialect).isEqualTo(StalkerSeriesDetailDialect.UNKNOWN)
    }
}
