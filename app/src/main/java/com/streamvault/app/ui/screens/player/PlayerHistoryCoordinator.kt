package com.streamvault.app.ui.screens.player

import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.usecase.MarkAsWatched
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Keeps player history reads and writes behind one feature-scoped boundary. */
class PlayerHistoryCoordinator @Inject constructor(
    private val repository: PlaybackHistoryRepository,
    private val watchNextManager: WatchNextManager,
    private val launcherRecommendationsManager: LauncherRecommendationsManager,
    private val markAsWatched: MarkAsWatched
) {
    internal fun recentlyWatchedByProvider(providerId: Long, limit: Int): Flow<List<PlaybackHistory>> =
        repository.getRecentlyWatchedByProvider(providerId, limit)

    internal suspend fun getPlaybackHistory(
        contentId: Long,
        contentType: ContentType,
        providerId: Long,
        seriesId: Long?,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): PlaybackHistory? = repository.getPlaybackHistory(
        contentId = contentId,
        contentType = contentType,
        providerId = providerId,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber
    )

    internal suspend fun recordPlayback(history: PlaybackHistory): Result<Unit> =
        repository.recordPlayback(history)

    internal suspend fun markAsWatched(history: PlaybackHistory): Result<Unit> =
        markAsWatched(history)

    internal suspend fun updateResumePosition(history: PlaybackHistory): Result<Unit> =
        repository.updateResumePosition(history)

    internal suspend fun updateWatchNextProgress(history: PlaybackHistory) {
        watchNextManager.updateWatchNextProgress(history)
    }

    internal suspend fun flushPendingProgress(): Result<Unit> =
        repository.flushPendingProgress()

    internal suspend fun refreshPlaybackSurfaces() {
        watchNextManager.refreshWatchNext()
        launcherRecommendationsManager.refreshRecommendations()
    }
}
