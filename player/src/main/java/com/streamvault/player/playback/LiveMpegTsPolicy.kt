@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.extractor.ts.TsExtractor

/** App-owned contract for a direct, long-lived MPEG-TS live input. */
internal data class LiveMpegTsPolicy(
    val extractorMode: ExtractorMode,
    val duration: DurationPolicy,
    val reconnect: ReconnectPolicy
) {
    enum class ExtractorMode {
        SINGLE_PMT
    }

    enum class DurationPolicy {
        LIVE_UNKNOWN
    }

    enum class ReconnectPolicy {
        RECREATE_SOURCE
    }

    val media3ExtractorMode: Int
        get() = when (extractorMode) {
            ExtractorMode.SINGLE_PMT -> TsExtractor.MODE_SINGLE_PMT
        }
}

internal val DIRECT_LIVE_MPEG_TS_POLICY = LiveMpegTsPolicy(
    extractorMode = LiveMpegTsPolicy.ExtractorMode.SINGLE_PMT,
    duration = LiveMpegTsPolicy.DurationPolicy.LIVE_UNKNOWN,
    reconnect = LiveMpegTsPolicy.ReconnectPolicy.RECREATE_SOURCE
)
