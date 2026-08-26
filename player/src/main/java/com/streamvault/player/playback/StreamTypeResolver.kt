package com.streamvault.player.playback

import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import java.net.URI
import java.util.Locale

enum class ResolvedStreamType {
    HLS,
    DASH,
    SMOOTH_STREAMING,
    PROGRESSIVE,
    MPEG_TS_LIVE,
    RTSP,
    UNKNOWN
}

object StreamTypeResolver {
    private val progressiveExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".mp3", ".aac", ".m4a")
    private val hlsMimeHints = listOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl"
    )
    private val dashMimeHints = listOf("application/dash+xml")
    private val smoothStreamingMimeHints = listOf("application/vnd.ms-sstr+xml", "text/xml")
    private val hlsQueryHints = listOf(
        "ext=m3u8",
        "extension=m3u8",
        "output=m3u8",
        "format=m3u8",
        "type=m3u8"
    )
    private val tsQueryHints = listOf(
        "ext=ts",
        "extension=ts",
        "output=ts",
        "format=ts",
        "type=ts"
    )
    private val hlsLiveAliases = setOf("sd", "hd", "fhd", "uhd", "4k", "playlist", "master", "index")

    fun resolve(streamInfo: StreamInfo, mimeType: String? = null): ResolvedStreamType {
        return when (streamInfo.streamType) {
            StreamType.HLS -> ResolvedStreamType.HLS
            StreamType.DASH -> ResolvedStreamType.DASH
            StreamType.SMOOTH_STREAMING -> ResolvedStreamType.SMOOTH_STREAMING
            StreamType.MPEG_TS -> ResolvedStreamType.MPEG_TS_LIVE
            StreamType.PROGRESSIVE -> ResolvedStreamType.PROGRESSIVE
            StreamType.RTSP -> ResolvedStreamType.RTSP
            else -> resolve(url = streamInfo.url, mimeType = mimeType, isLive = isLive(streamInfo))
        }
    }

    fun resolve(url: String, mimeType: String? = null, isLive: Boolean = false): ResolvedStreamType {
        val normalizedMimeType = mimeType?.trim()?.lowercase(Locale.ROOT)
        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        val path = uri?.path.orEmpty().ifBlank { url }
            .substringBefore('?')
            .substringBefore('#')
            .lowercase(Locale.ROOT)
        val query = uri?.rawQuery
            ?.lowercase(Locale.ROOT)
            ?: url.substringAfter('?', "")
                .substringBefore('#')
                .lowercase(Locale.ROOT)
        val lastSegment = path.substringAfterLast('/').trim()
        return when {
            scheme in setOf("rtsp", "rtsps") -> ResolvedStreamType.RTSP
            scheme in setOf("file", "content") && path.endsWith(".m3u8") -> ResolvedStreamType.HLS
            scheme in setOf("file", "content") -> ResolvedStreamType.PROGRESSIVE
            normalizedMimeType != null && hlsMimeHints.any(normalizedMimeType::contains) -> ResolvedStreamType.HLS
            normalizedMimeType != null && dashMimeHints.any(normalizedMimeType::contains) -> ResolvedStreamType.DASH
            normalizedMimeType != null && smoothStreamingMimeHints.any(normalizedMimeType::contains) && path.contains(".ism") ->
                ResolvedStreamType.SMOOTH_STREAMING
            hlsQueryHints.any(query::contains) -> ResolvedStreamType.HLS
            tsQueryHints.any(query::contains) -> ResolvedStreamType.MPEG_TS_LIVE
            path.contains(".m3u8") -> ResolvedStreamType.HLS
            path.contains(".mpd") -> ResolvedStreamType.DASH
            path.contains(".isml/manifest") || path.contains(".ism/manifest") || path.endsWith(".ism") || path.endsWith(".isml") ->
                ResolvedStreamType.SMOOTH_STREAMING
            path.endsWith(".ts") -> ResolvedStreamType.MPEG_TS_LIVE
            isLive && path.contains("/live/") && lastSegment in hlsLiveAliases -> ResolvedStreamType.HLS
            isLive && path.contains("/live/") && progressiveExtensions.none(path::endsWith) ->
                ResolvedStreamType.MPEG_TS_LIVE
            progressiveExtensions.any(path::endsWith) -> ResolvedStreamType.PROGRESSIVE
            else -> ResolvedStreamType.UNKNOWN
        }
    }

    private fun isLive(streamInfo: StreamInfo): Boolean {
        return streamInfo.streamType == StreamType.MPEG_TS ||
            streamInfo.url.contains("/live/", ignoreCase = true) ||
            streamInfo.catchUpUrl != null
    }
}
