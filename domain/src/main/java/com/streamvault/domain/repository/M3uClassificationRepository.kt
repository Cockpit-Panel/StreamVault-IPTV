package com.streamvault.domain.repository

import com.streamvault.domain.model.Result

enum class M3uClassificationTarget {
    LIVE,
    MOVIE,
    SERIES
}

data class M3uSeriesAssignment(
    val seriesName: String,
    val seasonNumber: Int = 1,
    /** Zero is reserved for an episode whose number could not be resolved automatically. */
    val episodeNumber: Int? = 1,
    val episodeTitle: String? = null
)

data class M3uCategoryItem(
    val channelId: Long,
    val title: String,
    val suggestedAssignment: M3uSeriesAssignment
)

/**
 * User-owned classification overrides for M3U entries.
 *
 * This repository is intentionally M3U-specific. Provider implementations for Xtream,
 * Stalker, and Jellyfin must never expose or call these operations.
 */
interface M3uClassificationRepository {
    suspend fun getCategoryItems(providerId: Long, categoryId: Long): Result<List<M3uCategoryItem>>

    suspend fun classifyChannel(
        providerId: Long,
        channelId: Long,
        target: M3uClassificationTarget,
        series: M3uSeriesAssignment? = null
    ): Result<Unit>

    suspend fun classifyChannelByStream(
        providerId: Long,
        streamId: Long,
        target: M3uClassificationTarget,
        series: M3uSeriesAssignment? = null
    ): Result<Unit>

    suspend fun classifyCategory(
        providerId: Long,
        categoryId: Long,
        target: M3uClassificationTarget,
        seriesAssignments: Map<Long, M3uSeriesAssignment> = emptyMap()
    ): Result<Int>

    suspend fun moveMovieBackToLive(providerId: Long, movieId: Long): Result<Unit>

    suspend fun moveEpisodeBackToLive(providerId: Long, episodeId: Long): Result<Unit>

    suspend fun moveSeriesBackToLive(providerId: Long, seriesId: Long): Result<Unit>
}
