package com.streamvault.domain.usecase

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.util.isPlaybackComplete
import com.streamvault.domain.util.shouldRethrowDomainFlowFailure
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.logging.Level
import java.util.logging.Logger

enum class ContinueWatchingScope {
    ALL_VOD,
    MOVIES,
    SERIES
}

sealed class ContinueWatchingResult {
    data class Items(val items: List<PlaybackHistory>) : ContinueWatchingResult()
    data object Degraded : ContinueWatchingResult()
}

class GetContinueWatching @Inject constructor(
    private val playbackHistoryRepository: PlaybackHistoryRepository
) {
    private val logger = Logger.getLogger("GetContinueWatching")

    operator fun invoke(
        providerId: Long,
        limit: Int = 24,
        scope: ContinueWatchingScope = ContinueWatchingScope.ALL_VOD,
        requireResumePosition: Boolean = false
    ): Flow<ContinueWatchingResult> = invoke(
        providerIds = setOf(providerId),
        limit = limit,
        scope = scope,
        requireResumePosition = requireResumePosition
    )

    operator fun invoke(
        providerIds: Set<Long>,
        limit: Int = 24,
        scope: ContinueWatchingScope = ContinueWatchingScope.ALL_VOD,
        requireResumePosition: Boolean = false
    ): Flow<ContinueWatchingResult> {
        val normalizedProviderIds = providerIds.filterTo(linkedSetOf()) { it > 0L }
        if (normalizedProviderIds.isEmpty()) {
            return flowOf(ContinueWatchingResult.Items(emptyList()))
        }

        val historyFlow = if (normalizedProviderIds.size == 1) {
            playbackHistoryRepository.getRecentlyWatchedByProvider(normalizedProviderIds.first(), limit)
        } else {
            playbackHistoryRepository.getRecentlyWatchedByProviders(normalizedProviderIds, limit)
        }

        return historyFlow
            .map<List<PlaybackHistory>, ContinueWatchingResult> { history ->
                ContinueWatchingResult.Items(
                    history.toContinueWatching(
                        limit = limit,
                        scope = scope,
                        requireResumePosition = requireResumePosition
                    )
                )
            }
            .catch { error ->
                if (error.shouldRethrowDomainFlowFailure()) {
                    throw error
                }
                logger.log(Level.WARNING, "Failed to build continue watching list", error)
                emit(ContinueWatchingResult.Degraded)
            }
    }

    private fun List<PlaybackHistory>.toContinueWatching(
        limit: Int,
        scope: ContinueWatchingScope,
        requireResumePosition: Boolean
    ): List<PlaybackHistory> = asSequence()
        .filter { entry -> scope.matches(entry.contentType) }
        .filterNot { entry -> entry.isCompleted() }
        .filter { entry -> !requireResumePosition || entry.isResumeEligible() }
        .distinctBy(::continueWatchingKey)
        .take(limit)
        .toList()

    private fun ContinueWatchingScope.matches(contentType: ContentType): Boolean = when (this) {
        ContinueWatchingScope.ALL_VOD -> contentType != ContentType.LIVE
        ContinueWatchingScope.MOVIES -> contentType == ContentType.MOVIE
        ContinueWatchingScope.SERIES -> contentType == ContentType.SERIES || contentType == ContentType.SERIES_EPISODE
    }

    private fun continueWatchingKey(entry: PlaybackHistory): String = when (entry.contentType) {
        ContentType.MOVIE -> "movie:${entry.contentId}"
        ContentType.SERIES,
        ContentType.SERIES_EPISODE -> "series:${entry.seriesId?.takeIf { it > 0L } ?: entry.contentId}"
        ContentType.LIVE -> "live:${entry.contentId}"
        ContentType.VOD -> "vod:${entry.contentId}"
    }

    private fun PlaybackHistory.isResumeEligible(): Boolean = when (contentType) {
        ContentType.MOVIE,
        ContentType.SERIES_EPISODE -> resumePositionMs > 0L
        ContentType.SERIES -> true
        ContentType.LIVE,
        ContentType.VOD -> false
    }

    private fun PlaybackHistory.isCompleted(): Boolean = when (contentType) {
        ContentType.MOVIE,
        ContentType.SERIES_EPISODE -> isPlaybackComplete(resumePositionMs, totalDurationMs)
        ContentType.SERIES,
        ContentType.LIVE,
        ContentType.VOD -> false
    }
}
