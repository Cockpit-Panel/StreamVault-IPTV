package com.streamvault.domain.model

data class PlaybackHistory(
    val id: Long = 0,
    val contentId: Long,
    val contentType: ContentType,
    val providerId: Long,
    /** Provider/catalog identity used by backup restore; not persisted in playback history. */
    val remoteContentId: String? = null,
    /** Stable provider/catalog identity of the parent series for episode history. */
    val remoteSeriesId: String? = null,
    val title: String,
    val posterUrl: String? = null,
    val streamUrl: String,
    val resumePositionMs: Long = 0,
    val totalDurationMs: Long = 0,
    val lastWatchedAt: Long = 0,
    val watchCount: Int = 1,
    val watchedStatus: PlaybackWatchedStatus = PlaybackWatchedStatus.IN_PROGRESS,
    val seriesId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
) {
    init {
        require(resumePositionMs >= 0) { "resumePositionMs must be non-negative" }
        require(totalDurationMs >= 0) { "totalDurationMs must be non-negative" }
        require(watchCount >= 1) { "watchCount must be at least 1" }
        require(lastWatchedAt >= 0) { "lastWatchedAt must be non-negative" }
        seasonNumber?.let { require(it >= 0) { "seasonNumber must be non-negative" } }
        episodeNumber?.let { require(it >= 0) { "episodeNumber must be non-negative" } }
    }
}

enum class PlaybackWatchedStatus {
    IN_PROGRESS,
    COMPLETED_AUTO,
    COMPLETED_MANUAL
}
