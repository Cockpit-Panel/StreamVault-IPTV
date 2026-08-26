package com.streamvault.domain.model

import java.io.Serializable

data class VodVariantObservation(
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastSuccessfulAt: Long = 0L,
    val lastFailedAt: Long = 0L
) {
    init {
        require(successCount >= 0) { "successCount must be non-negative" }
        require(failureCount >= 0) { "failureCount must be non-negative" }
        require(lastSuccessfulAt >= 0L) { "lastSuccessfulAt must be non-negative" }
        require(lastFailedAt >= 0L) { "lastFailedAt must be non-negative" }
    }
}

data class VodMovieVariant(
    val rawMovieId: Long,
    val name: String,
    val streamUrl: String,
    val streamId: Long,
    val containerExtension: String?,
    val releaseDate: String?,
    val year: String?,
    val durationSeconds: Int,
    val rating: Float,
    val addedAt: Long,
    val qualityScore: Int,
    val recencyScore: Long,
    val reliabilityScore: Int,
    val label: String
) : Serializable

data class VodSeriesVariant(
    val rawSeriesId: Long,
    val name: String,
    val seriesId: Long,
    val providerSeriesId: String?,
    val releaseDate: String?,
    val tmdbId: Long?,
    val episodeRunTime: String?,
    val rating: Float,
    val lastModified: Long,
    val qualityScore: Int,
    val recencyScore: Long,
    val reliabilityScore: Int,
    val label: String
) : Serializable
