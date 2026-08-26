package com.streamvault.app.ui.screens.player

import com.streamvault.app.cast.CastConnectionState
import com.streamvault.app.cast.CastManager
import com.streamvault.app.cast.CastMediaRequest
import com.streamvault.app.cast.CastMediaRequestBuildResult
import com.streamvault.app.cast.CastMediaRequestFactory
import com.streamvault.app.cast.CastPlaybackCoordinator
import com.streamvault.app.cast.CastPlaybackEvent
import com.streamvault.app.cast.CastStartResult
import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Bundles cast route lifecycle, request construction, and playback events for the player. */
class PlayerCastCoordinator @Inject constructor(
    private val castManager: CastManager,
    private val requestFactory: CastMediaRequestFactory,
    private val playbackCoordinator: CastPlaybackCoordinator
) {
    val connectionState: StateFlow<CastConnectionState> = castManager.connectionState
    val playbackEvents: Flow<CastPlaybackEvent> = playbackCoordinator.playbackEvents

    internal suspend fun startCasting(request: CastMediaRequest): CastStartResult =
        playbackCoordinator.startCasting(request)

    internal fun stopCasting() {
        castManager.stopCasting()
    }

    internal fun buildFromStreamInfo(
        streamInfo: StreamInfo,
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        isLive: Boolean,
        startPositionMs: Long
    ): CastMediaRequestBuildResult = requestFactory.buildFromStreamInfo(
        streamInfo = streamInfo,
        title = title,
        subtitle = subtitle,
        artworkUrl = artworkUrl,
        isLive = isLive,
        startPositionMs = startPositionMs
    )
}
