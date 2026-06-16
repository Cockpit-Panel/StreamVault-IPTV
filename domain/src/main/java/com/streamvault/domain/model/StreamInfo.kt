package com.streamvault.domain.model

import com.streamvault.domain.util.StreamEntryUrlPolicy

data class StreamInfo(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val streamType: StreamType = StreamType.UNKNOWN,
    val containerExtension: String? = null,
    val catchUpUrl: String? = null,
    val expirationTime: Long? = null,
    val drmInfo: DrmInfo? = null
) {
    init {
        require(url.isNotBlank()) { "StreamInfo url must not be blank" }
        expirationTime?.let { require(it >= 0) { "StreamInfo expirationTime must be non-negative" } }
    }
}

data class DrmInfo(
    val scheme: DrmScheme,
    val licenseUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val multiSession: Boolean = false,
    val forceDefaultLicenseUrl: Boolean = false,
    val playClearContentWithoutKey: Boolean = false
) {
    init {
        require(licenseUrl.isNotBlank()) { "DrmInfo licenseUrl must not be blank" }
        require(StreamEntryUrlPolicy.isAllowed(licenseUrl)) {
            "DrmInfo licenseUrl must use an allowed stream-entry URL scheme"
        }
    }
}

enum class DrmScheme {
    WIDEVINE,
    PLAYREADY,
    CLEARKEY
}

enum class StreamType {
    HLS,
    DASH,
    SMOOTH_STREAMING,
    MPEG_TS,
    PROGRESSIVE,
    RTSP,    // PE-H03: native RTSP via Media3 RtspMediaSource
    UNKNOWN;

    companion object {
        fun fromContainerExtension(ext: String?): StreamType {
            return when (ext?.trim()?.removePrefix(".")?.lowercase()) {
                "ts" -> MPEG_TS
                "m3u8" -> HLS
                "mpd" -> DASH
                "ism", "isml" -> SMOOTH_STREAMING
                "mp4", "mkv", "avi", "mov", "mp3", "aac", "m4a", "flv", "webm" -> PROGRESSIVE
                else -> UNKNOWN
            }
        }
    }
}

enum class DecoderMode {
    AUTO,
    HARDWARE,
    SOFTWARE,
    COMPATIBILITY
}

enum class PlayerSurfaceMode {
    AUTO,
    SURFACE_VIEW,
    TEXTURE_VIEW
}

enum class VodHttpProtocolMode {
    COMPATIBILITY_HTTP1,
    AUTO
}

enum class LiveStreamFormatMode {
    AUTO,
    HLS,
    MPEG_TS
}
