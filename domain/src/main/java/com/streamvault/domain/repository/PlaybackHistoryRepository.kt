package com.streamvault.domain.repository

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface PlaybackHistoryRepository {
    fun getRecentlyWatched(limit: Int = 100): Flow<List<PlaybackHistory>>
    fun getRecentlyWatchedByProvider(providerId: Long, limit: Int = 100): Flow<List<PlaybackHistory>>
    fun getRecentlyWatchedByProviders(providerIds: Set<Long>, limit: Int = 100): Flow<List<PlaybackHistory>>
    fun getUnwatchedCount(providerId: Long, seriesId: Long): Flow<Int>
    suspend fun getPlaybackHistory(
        contentId: Long,
        contentType: ContentType,
        providerId: Long,
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): PlaybackHistory?

    suspend fun markAsWatched(history: PlaybackHistory): Result<Unit>
    suspend fun recordPlayback(history: PlaybackHistory): Result<Unit>
    suspend fun updateResumePosition(history: PlaybackHistory): Result<Unit>
    suspend fun flushPendingProgress(): Result<Unit>
    suspend fun removeFromHistory(contentId: Long, contentType: ContentType, providerId: Long): Result<Unit>
    suspend fun clearAllHistory(): Result<Unit>
    suspend fun clearHistoryForProvider(providerId: Long): Result<Unit>
    suspend fun clearLiveHistoryForProvider(providerId: Long): Result<Unit>
    suspend fun clearVodHistory(): Result<Unit>
}
