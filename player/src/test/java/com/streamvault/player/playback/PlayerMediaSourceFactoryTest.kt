package com.streamvault.player.playback

import androidx.media3.extractor.ts.TsExtractor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerMediaSourceFactoryTest {

    @Test
    fun `direct live mpeg ts policy declares single pmt and live duration`() {
        assertThat(DIRECT_LIVE_MPEG_TS_POLICY.extractorMode)
            .isEqualTo(LiveMpegTsPolicy.ExtractorMode.SINGLE_PMT)
        assertThat(DIRECT_LIVE_MPEG_TS_POLICY.duration)
            .isEqualTo(LiveMpegTsPolicy.DurationPolicy.LIVE_UNKNOWN)
        assertThat(DIRECT_LIVE_MPEG_TS_POLICY.reconnect)
            .isEqualTo(LiveMpegTsPolicy.ReconnectPolicy.RECREATE_SOURCE)
    }

    @Test
    fun `policy maps to the Media3 single pmt mode`() {
        assertThat(DIRECT_LIVE_MPEG_TS_POLICY.media3ExtractorMode)
            .isEqualTo(TsExtractor.MODE_SINGLE_PMT)
    }
}
