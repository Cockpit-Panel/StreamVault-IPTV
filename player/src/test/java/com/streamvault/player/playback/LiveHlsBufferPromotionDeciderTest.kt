package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import org.junit.Test

class LiveHlsBufferPromotionDeciderTest {

    @Test
    fun `unchanged policy does not request promotion`() {
        val decision = LiveHlsBufferPromotionDecider.decide(
            bufferMode = PlaybackBufferMode.AUTO,
            resolvedStreamType = ResolvedStreamType.HLS,
            isLive = true,
            mediaAlreadyPromoted = false,
            currentPolicyLabel = "stable-live",
            streamInfo = StreamInfo(url = "https://example.com/live/channel.m3u8"),
            observedVideoFormat = VideoFormat(width = 1_920, height = 1_080),
            compatibilityMode = false,
            lowMemoryDevice = false
        )

        assertThat(decision).isNull()
    }

    @Test
    fun `observed uhd promotion requests stronger policy`() {
        val decision = LiveHlsBufferPromotionDecider.decide(
            bufferMode = PlaybackBufferMode.AUTO,
            resolvedStreamType = ResolvedStreamType.HLS,
            isLive = true,
            mediaAlreadyPromoted = false,
            currentPolicyLabel = "stable-live",
            streamInfo = StreamInfo(url = "https://example.com/live/channel.m3u8"),
            observedVideoFormat = VideoFormat(width = 3_840, height = 2_160),
            compatibilityMode = false,
            lowMemoryDevice = false
        )

        assertThat(decision).isNotNull()
        assertThat(decision?.policy?.label).isEqualTo("auto-uhd-live-hls")
        assertThat(decision?.qualityReason).isEqualTo("observed-width-3840")
    }

    @Test
    fun `repeated observation after promotion does not request another promotion`() {
        val decision = LiveHlsBufferPromotionDecider.decide(
            bufferMode = PlaybackBufferMode.AUTO,
            resolvedStreamType = ResolvedStreamType.HLS,
            isLive = true,
            mediaAlreadyPromoted = true,
            currentPolicyLabel = "auto-uhd-live-hls",
            streamInfo = StreamInfo(url = "https://example.com/live/channel.m3u8"),
            observedVideoFormat = VideoFormat(width = 3_840, height = 2_160),
            compatibilityMode = false,
            lowMemoryDevice = false
        )

        assertThat(decision).isNull()
    }

    @Test
    fun `manual large mode does not request observed auto promotion`() {
        val decision = LiveHlsBufferPromotionDecider.decide(
            bufferMode = PlaybackBufferMode.LARGE,
            resolvedStreamType = ResolvedStreamType.HLS,
            isLive = true,
            mediaAlreadyPromoted = false,
            currentPolicyLabel = "large-live",
            streamInfo = StreamInfo(url = "https://example.com/live/channel.m3u8"),
            observedVideoFormat = VideoFormat(width = 3_840, height = 2_160),
            compatibilityMode = false,
            lowMemoryDevice = false
        )

        assertThat(decision).isNull()
    }
}
