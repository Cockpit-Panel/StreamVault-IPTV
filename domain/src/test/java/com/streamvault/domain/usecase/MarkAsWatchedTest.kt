package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.PlaybackHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MarkAsWatchedTest {

    @Test
    fun complete_progress_is_promoted_to_total_duration_before_persisting() = runTest {
        val repository = FakePlaybackHistoryRepository()
        val useCase = MarkAsWatched(repository)

        useCase(history(resumePositionMs = 96_000L, totalDurationMs = 100_000L))

        assertThat(repository.lastMarkedHistory?.resumePositionMs).isEqualTo(100_000L)
    }

    @Test
    fun incomplete_progress_is_persisted_without_forcing_completion() = runTest {
        val repository = FakePlaybackHistoryRepository()
        val useCase = MarkAsWatched(repository)

        useCase(history(resumePositionMs = 40_000L, totalDurationMs = 100_000L))

        assertThat(repository.lastMarkedHistory?.resumePositionMs).isEqualTo(40_000L)
    }

    private fun history(
        resumePositionMs: Long,
        totalDurationMs: Long
    ) = PlaybackHistory(
        contentId = 1L,
        contentType = ContentType.MOVIE,
        providerId = 1L,
        title = "Movie",
        streamUrl = "https://example.com/movie.mkv",
        resumePositionMs = resumePositionMs,
        totalDurationMs = totalDurationMs,
        lastWatchedAt = 1L
    )

    private class FakePlaybackHistoryRepository : PlaybackHistoryRepository {
        var lastMarkedHistory: PlaybackHistory? = null

        override fun getRecentlyWatched(limit: Int): Flow<List<PlaybackHistory>> = flowOf(emptyList())
        override fun getRecentlyWatchedByProvider(providerId: Long, limit: Int): Flow<List<PlaybackHistory>> = flowOf(emptyList())
        override fun getRecentlyWatchedByProviders(providerIds: Set<Long>, limit: Int): Flow<List<PlaybackHistory>> = flowOf(emptyList())
        override fun getUnwatchedCount(providerId: Long, seriesId: Long): Flow<Int> = flowOf(0)
        override suspend fun getPlaybackHistory(
            contentId: Long,
            contentType: ContentType,
            providerId: Long,
            seriesId: Long?,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): PlaybackHistory? = null
        override suspend fun markAsWatched(history: PlaybackHistory): com.streamvault.domain.model.Result<Unit> {
            lastMarkedHistory = history
            return com.streamvault.domain.model.Result.success(Unit)
        }
        override suspend fun recordPlayback(history: PlaybackHistory) = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun updateResumePosition(history: PlaybackHistory) = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun flushPendingProgress() = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun removeFromHistory(contentId: Long, contentType: ContentType, providerId: Long) = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun clearAllHistory() = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun clearHistoryForProvider(providerId: Long) = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun clearLiveHistoryForProvider(providerId: Long) = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun clearVodHistory() = com.streamvault.domain.model.Result.success(Unit)
    }
}
