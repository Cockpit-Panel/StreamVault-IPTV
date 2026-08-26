package com.streamvault.app.cast

import com.streamvault.domain.model.PlaybackTransportPolicy

/**
 * Media payload sent to a Chromecast receiver.
 *
 * **Live channel URL strategy:** For Xtream live channels the [url] must be
 * the credential-based portal URL (no expiry tokens) so that long-running Cast
 * sessions are not interrupted when a tokenized CDN URL expires.
 * Player casting passes `preferStableUrl = true` when resolving the live
 * stream URL.
 */
data class CastMediaRequest(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val mimeType: String? = null,
    val isLive: Boolean = false,
    val startPositionMs: Long = 0L,
    val rewriteRequiredReason: CastRewriteRequiredReason? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val playbackTransportPolicy: PlaybackTransportPolicy? = null,
    val allowInvalidSsl: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null
) {
    val requiresCastRewrite: Boolean
        get() = rewriteRequiredReason != null
}

enum class CastRewriteRequiredReason {
    LOCAL_URI,
    CUSTOM_HEADERS,
    CUSTOM_USER_AGENT,
    PROXY,
    INVALID_SSL,
    SCOPED_TRANSPORT
}

enum class CastConnectionState {
    UNAVAILABLE,
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class CastStartResult {
    STARTED,
    ROUTE_SELECTION_REQUIRED,
    UNAVAILABLE,
    UNSUPPORTED
}

sealed interface CastPlaybackEvent {
    data class MediaLoadSucceeded(val title: String) : CastPlaybackEvent
    data class MediaLoadFailed(val title: String?, val statusCode: Int? = null) : CastPlaybackEvent
    data class SessionStartFailed(val errorCode: Int) : CastPlaybackEvent
    data class ReceiverUnavailable(val title: String?) : CastPlaybackEvent
    data object RouteSelectionCancelled : CastPlaybackEvent
}

enum class CastPlaybackReportMode {
    NONE,
    FAILURES_ONLY,
    SUCCESS_AND_FAILURE
}

sealed interface CastUiEvent {
    data object OpenRouteChooser : CastUiEvent
    data class ShowMessage(val messageResId: Int) : CastUiEvent
}
