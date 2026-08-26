package com.streamvault.domain.model

data class Series(
    val id: Long,
    val name: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: Float = 0f,
    val tmdbId: Long? = null,
    val youtubeTrailer: String? = null,
    val isFavorite: Boolean = false,
    val providerId: Long = 0,
    val seasons: List<Season> = emptyList(),
    val episodeRunTime: String? = null,
    val lastModified: Long = 0L,
    val isAdult: Boolean = false,
    val isUserProtected: Boolean = false,
    val seriesId: Long = 0L,
    val providerSeriesId: String? = null,
    val logicalGroupId: String = "",
    val selectedVariantId: Long? = null,
    val variants: List<VodSeriesVariant> = emptyList(),
    val duplicateConfidence: VodDuplicateConfidence = VodDuplicateConfidence.NONE,
    val variantLabel: String? = null,
    val catalogOrigin: SeriesCatalogOrigin = SeriesCatalogOrigin.NATIVE,
    /** Provider-neutral logical playback URL used when episode rows do not carry their own command. */
    val episodePlaybackTemplateUrl: String? = null
) {
    init {
        require(rating in 0f..10f) { "rating must be between 0 and 10" }
        require(lastModified >= 0) { "lastModified must be non-negative" }
    }
}

enum class SeriesCatalogOrigin {
    NATIVE,
    VOD_DERIVED
}

data class Season(
    val seasonNumber: Int,
    val name: String = "Season $seasonNumber",
    val coverUrl: String? = null,
    val episodes: List<Episode> = emptyList(),
    val airDate: String? = null,
    val episodeCount: Int = 0
) {
    init {
        require(seasonNumber >= 0) { "seasonNumber must be non-negative" }
        require(episodeCount >= 0) { "episodeCount must be non-negative" }
    }
}

data class Episode(
    val id: Long,
    val title: String,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val streamUrl: String = "",
    val containerExtension: String? = null,
    val coverUrl: String? = null,
    val plot: String? = null,
    val duration: String? = null,
    val durationSeconds: Int = 0,
    val rating: Float = 0f,
    val releaseDate: String? = null,
    val seriesId: Long = 0,
    val providerId: Long = 0,
    val watchProgress: Long = 0L,
    val lastWatchedAt: Long = 0L,
    val isAdult: Boolean = false,
    val isUserProtected: Boolean = false,
    val episodeId: Long = 0L
) {
    init {
        require(episodeNumber >= 0) { "episodeNumber must be non-negative" }
        require(seasonNumber >= 0) { "seasonNumber must be non-negative" }
        require(durationSeconds >= 0) { "durationSeconds must be non-negative" }
        require(rating in 0f..10f) { "rating must be between 0 and 10" }
        require(watchProgress >= 0) { "watchProgress must be non-negative" }
        require(lastWatchedAt >= 0) { "lastWatchedAt must be non-negative" }
    }
}
