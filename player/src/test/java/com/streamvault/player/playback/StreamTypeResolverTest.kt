package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamTypeResolverTest {

    @Test
    fun `m3u8 resolves to HLS`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/channel.m3u8"))
            .isEqualTo(ResolvedStreamType.HLS)
    }

    @Test
    fun `mpd resolves to DASH`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/manifest.mpd"))
            .isEqualTo(ResolvedStreamType.DASH)
    }

    @Test
    fun `isml manifest resolves to SmoothStreaming`() {
        assertThat(StreamTypeResolver.resolve("https://example.com/index.isml/Manifest"))
            .isEqualTo(ResolvedStreamType.SMOOTH_STREAMING)
    }

    @Test
    fun `live url resolves to MPEG TS live`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/live/channel1", isLive = true))
            .isEqualTo(ResolvedStreamType.MPEG_TS_LIVE)
    }

    @Test
    fun `live quality alias resolves to HLS`() {
        assertThat(StreamTypeResolver.resolve("https://example.com/live/AEEast/hd", isLive = true))
            .isEqualTo(ResolvedStreamType.HLS)
    }

    @Test
    fun `query hint ext m3u8 resolves to HLS`() {
        assertThat(StreamTypeResolver.resolve("https://example.com/play?id=7&ext=m3u8", isLive = true))
            .isEqualTo(ResolvedStreamType.HLS)
    }

    @Test
    fun `query hint ext ts resolves to MPEG TS live`() {
        assertThat(StreamTypeResolver.resolve("https://example.com/play?id=7&ext=ts", isLive = true))
            .isEqualTo(ResolvedStreamType.MPEG_TS_LIVE)
    }

    @Test
    fun `stalker extension ts query resolves to MPEG TS live`() {
        assertThat(
            StreamTypeResolver.resolve(
                "http://portal.example.com/play/live.php?stream=1544246&extension=ts&play_token=token"
            )
        ).isEqualTo(ResolvedStreamType.MPEG_TS_LIVE)
    }

    @Test
    fun `stalker extension m3u8 query resolves to HLS`() {
        assertThat(
            StreamTypeResolver.resolve(
                "http://portal.example.com/play/live.php?stream=1544246&extension=m3u8"
            )
        ).isEqualTo(ResolvedStreamType.HLS)
    }

    @Test
    fun `mp4 resolves to progressive`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/movie.mp4"))
            .isEqualTo(ResolvedStreamType.PROGRESSIVE)
    }

    @Test
    fun `unknown url resolves to unknown`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/stream"))
            .isEqualTo(ResolvedStreamType.UNKNOWN)
    }
}
