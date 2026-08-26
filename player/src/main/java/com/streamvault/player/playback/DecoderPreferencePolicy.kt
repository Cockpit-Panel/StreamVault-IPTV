package com.streamvault.player.playback

import com.streamvault.domain.model.DecoderMode

interface DecoderPreferencePolicy {
    fun preferredMode(requestedMode: DecoderMode, mediaId: String): DecoderMode
    fun onDecoderInitFailure(requestedMode: DecoderMode, mediaId: String): DecoderMode?
    fun resetForMedia(mediaId: String)
}

internal fun shouldForceSoftwareForAmbiguousDecoderFallback(
    audioFallbackMode: DecoderMode?,
    videoFallbackMode: DecoderMode?
): Boolean = audioFallbackMode == DecoderMode.SOFTWARE || videoFallbackMode == DecoderMode.SOFTWARE

class DefaultDecoderPreferencePolicy : DecoderPreferencePolicy {
    private val softwareRetriedMediaIds = mutableSetOf<String>()

    override fun preferredMode(requestedMode: DecoderMode, mediaId: String): DecoderMode {
        return when (requestedMode) {
            DecoderMode.SOFTWARE, DecoderMode.COMPATIBILITY -> DecoderMode.SOFTWARE
            DecoderMode.AUTO -> {
                if (mediaId in softwareRetriedMediaIds) DecoderMode.SOFTWARE else DecoderMode.HARDWARE
            }
            DecoderMode.HARDWARE -> DecoderMode.HARDWARE
        }
    }

    override fun onDecoderInitFailure(requestedMode: DecoderMode, mediaId: String): DecoderMode? {
        if (requestedMode != DecoderMode.AUTO) return null
        if (!softwareRetriedMediaIds.add(mediaId)) return null
        return DecoderMode.SOFTWARE
    }

    override fun resetForMedia(mediaId: String) {
        softwareRetriedMediaIds.remove(mediaId)
    }
}

