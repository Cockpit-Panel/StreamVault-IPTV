package com.streamvault.data.remote.xtream

import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.domain.provider.PlaybackObservation
import com.streamvault.domain.provider.StalkerPlaybackObservation
import javax.inject.Inject
import javax.inject.Singleton

/** Commit boundary used by playback resolution; implementations may persist or audit observations. */
fun interface PlaybackObservationSink {
    suspend fun persist(observations: List<PlaybackObservation>)
}

/** Persists resolver observations behind generation-aware compare-and-set storage. */
@Singleton
class PlaybackObservationCoordinator @Inject constructor(
    private val stalkerStateStore: StalkerPortalStateStore
) : PlaybackObservationSink {
    override suspend fun persist(observations: List<PlaybackObservation>) {
        observations.forEach { observation ->
            when (observation) {
                is StalkerPlaybackObservation -> stalkerStateStore.recordPlayback(
                    providerId = observation.providerId,
                    playbackMode = observation.playbackMode,
                    endpointPreference = observation.endpointPreference,
                    cookieMode = observation.cookieMode,
                    backendHint = observation.backendHint,
                    configurationGeneration = observation.configurationGeneration
                )
            }
        }
    }
}
