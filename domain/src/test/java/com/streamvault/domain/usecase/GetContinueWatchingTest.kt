package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.PlaybackHistoryRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GetContinueWatchingTest {

    @Test
    fun collapses_multiple_episode_entries_into_one_series_resume() = runTest {
        val useCase = GetContinueWatching(
            playbackHistoryRepository = FakePlaybackHistoryRepository(
                history = listOf(
                    history(contentId = 21L, type = ContentType.SERIES_EPISODE, seriesId = 7L, lastWatchedAt = 300L, resumePositionMs = 50_000L),
                    history(contentId = 20L, type = ContentType.SERIES_EPISODE, seriesId = 7L, lastWatchedAt = 200L, resumePositionMs = 40_000L),
                    history(contentId = 11L, type = ContentType.MOVIE, lastWatchedAt = 100L, resumePositionMs = 10_000L)
                )
            )
        )

        val result = useCase(providerId = 1L, limit = 5).collectOnce()

        assertThat(result).hasSize(2)
        assertThat(result.first().contentId).isEqualTo(21L)
        assertThat(result.last().contentId).isEqualTo(11L)
    }

    @Test
    fun movie_scope_keeps_only_movies() = runTest {
        val useCase = GetContinueWatching(
            playbackHistoryRepository = FakePlaybackHistoryRepository(
                history = listOf(
                    history(contentId = 1L, type = ContentType.MOVIE, lastWatchedAt = 300L, resumePositionMs = 15_000L),
                    history(contentId = 2L, type = ContentType.SERIES_EPISODE, seriesId = 9L, lastWatchedAt = 200L, resumePositionMs = 15_000L)
                )
            )
        )

        val result = useCase(providerId = 1L, scope = ContinueWatchingScope.MOVIES).collectOnce()

        assertThat(result.map { it.contentId }).containsExactly(1L)
    }

    @Test
    fun require_resume_position_filters_out_unstarted_movies_and_episodes() = runTest {
        val useCase = GetContinueWatching(
            playbackHistoryRepository = FakePlaybackHistoryRepository(
                history = listOf(
                    history(contentId = 1L, type = ContentType.MOVIE, lastWatchedAt = 400L, resumePositionMs = 0L),
                    history(contentId = 2L, type = ContentType.SERIES, seriesId = 2L, lastWatchedAt = 300L, resumePositionMs = 0L),
                    history(contentId = 3L, type = ContentType.SERIES_EPISODE, seriesId = 3L, lastWatchedAt = 200L, resumePositionMs = 0L),
                    history(contentId = 4L, type = ContentType.MOVIE, lastWatchedAt = 100L, resumePositionMs = 25_000L)
                )
            )
        )

        val result = useCase(providerId = 1L, requireResumePosition = true).collectOnce()

        assertThat(result.map { it.contentId }).containsExactly(2L, 4L).inOrder()
    }

    @Test
    fun aggregates_selected_provider_ids_only() = runTest {
        val useCase = GetContinueWatching(
            playbackHistoryRepository = FakePlaybackHistoryRepository(
                history = listOf(
                    history(contentId = 20L, type = ContentType.SERIES_EPISODE, providerId = 2L, seriesId = 5L, lastWatchedAt = 300L, resumePositionMs = 15_000L),
                    history(contentId = 10L, type = ContentType.MOVIE, providerId = 1L, lastWatchedAt = 200L, resumePositionMs = 12_000L),
                    history(contentId = 30L, type = ContentType.MOVIE, providerId = 3L, lastWatchedAt = 100L, resumePositionMs = 9_000L)
                )
            )
        )

        val result = useCase(setOf(1L, 2L), limit = 5).collectOnce()

        assertThat(result.map { it.providerId to it.contentId }).containsExactly(
            2L to 20L,
            1L to 10L
        ).inOrder()
    }

    @Test
    fun multi_provider_requests_use_provider_set_repository_path() = runTest {
        val repository = FakePlaybackHistoryRepository(
            history = listOf(
                history(contentId = 99L, type = ContentType.MOVIE, providerId = 99L, lastWatchedAt = 500L, resumePositionMs = 12_000L)
            ),
            multiProviderHistory = listOf(
                history(contentId = 20L, type = ContentType.SERIES_EPISODE, providerId = 2L, seriesId = 5L, lastWatchedAt = 300L, resumePositionMs = 15_000L),
                history(contentId = 10L, type = ContentType.MOVIE, providerId = 1L, lastWatchedAt = 200L, resumePositionMs = 12_000L)
            )
        )
        val useCase = GetContinueWatching(playbackHistoryRepository = repository)

        val result = useCase(setOf(1L, 2L), limit = 5).collectOnce()

        assertThat(result.map { it.providerId to it.contentId }).containsExactly(
            2L to 20L,
            1L to 10L
        ).inOrder()
        assertThat(repository.lastRequestedProviderIds).containsExactly(1L, 2L)
    }

    @Test
    fun returns_degraded_on_recoverable_io_failure() = runTest {
        val useCase = GetContinueWatching(
            playbackHistoryRepository = FakePlaybackHistoryRepository(
                historyFlow = flow { throw IOException("network failure") }
            )
        )

        val result = useCase(providerId = 1L).first()

        assertThat(result).isEqualTo(ContinueWatchingResult.Degraded)
    }

    @Test
    fun rethrows_non_io_upstream_failures() = runTest {
        val expected = IllegalStateException("database broken")
        val useCase = GetContinueWatching(
            playbackHistoryRepository = FakePlaybackHistoryRepository(
                historyFlow = flow { throw expected }
            )
        )

        val thrown = try {
            useCase(providerId = 1L).first()
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown?.message).isEqualTo(expected.message)
    }

    private suspend fun Flow<ContinueWatchingResult>.collectOnce(): List<PlaybackHistory> =
        first().let { result ->
            check(result is ContinueWatchingResult.Items) { "Expected Items but got $result" }
            result.items
        }

    private fun history(
        contentId: Long,
        type: ContentType,
        providerId: Long = 1L,
        seriesId: Long? = null,
        lastWatchedAt: Long,
        resumePositionMs: Long
    ) = PlaybackHistory(
        contentId = contentId,
        contentType = type,
        providerId = providerId,
        title = "$type-$contentId",
        streamUrl = "https://example.com/$contentId",
        resumePositionMs = resumePositionMs,
        totalDurationMs = 120_000L,
        lastWatchedAt = lastWatchedAt,
        seriesId = seriesId
    )

    private class FakePlaybackHistoryRepository(
        private val history: List<PlaybackHistory> = emptyList(),
        private val multiProviderHistory: List<PlaybackHistory> = history,
        private val historyFlow: Flow<List<PlaybackHistory>>? = null
    ) : PlaybackHistoryRepository {
        var lastRequestedProviderIds: Set<Long>? = null

        override fun getRecentlyWatched(limit: Int): Flow<List<PlaybackHistory>> = historyFlow ?: flowOf(history.take(limit))
        override fun getRecentlyWatchedByProvider(providerId: Long, limit: Int): Flow<List<PlaybackHistory>> =
            historyFlow ?: flowOf(history.filter { it.providerId == providerId }.take(limit))
        override fun getRecentlyWatchedByProviders(providerIds: Set<Long>, limit: Int): Flow<List<PlaybackHistory>> {
            lastRequestedProviderIds = providerIds
            return historyFlow ?: flowOf(multiProviderHistory.filter { it.providerId in providerIds }.take(limit))
        }
        override fun getUnwatchedCount(providerId: Long, seriesId: Long): Flow<Int> = flowOf(0)
        override suspend fun getPlaybackHistory(
            contentId: Long,
            contentType: ContentType,
            providerId: Long,
            seriesId: Long?,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): PlaybackHistory? = null
        override suspend fun markAsWatched(history: PlaybackHistory) = com.streamvault.domain.model.Result.success(Unit)
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
