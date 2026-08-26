package com.streamvault.data.sync

import com.streamvault.data.remote.jellyfin.JellyfinCatalogLimitException
import com.streamvault.data.remote.jellyfin.JellyfinPaginationException

internal enum class JellyfinCatalogPhase {
    MOVIES,
    SERIES,
    READY
}

internal data class JellyfinCatalogCheckpoint(
    val movieSessionId: Long,
    val seriesSessionId: Long,
    val phase: JellyfinCatalogPhase,
    val movieStartIndex: Int = 0,
    val movieTotal: Int? = null,
    val seriesStartIndex: Int = 0,
    val seriesTotal: Int? = null
) {
    fun afterMoviePage(reportedTotal: Int, pageItemCount: Int, stagedCount: Int): JellyfinCatalogCheckpoint {
        val expectedTotal = movieTotal ?: reportedTotal
        validatePage("movie", movieStartIndex, expectedTotal, reportedTotal, pageItemCount, stagedCount, 200_000)
        return copy(movieStartIndex = movieStartIndex + pageItemCount, movieTotal = expectedTotal)
    }

    fun afterSeriesPage(reportedTotal: Int, pageItemCount: Int, stagedCount: Int): JellyfinCatalogCheckpoint {
        val expectedTotal = seriesTotal ?: reportedTotal
        validatePage("series", seriesStartIndex, expectedTotal, reportedTotal, pageItemCount, stagedCount, 100_000)
        return copy(seriesStartIndex = seriesStartIndex + pageItemCount, seriesTotal = expectedTotal)
    }

    fun isConsistent(movieStageCount: Int, seriesStageCount: Int): Boolean {
        val movieComplete = movieTotal != null && movieStartIndex == movieTotal
        val seriesComplete = seriesTotal != null && seriesStartIndex == seriesTotal
        return movieStageCount == movieStartIndex &&
            seriesStageCount == seriesStartIndex &&
            movieTotal?.let { movieStartIndex <= it } != false &&
            seriesTotal?.let { seriesStartIndex <= it } != false &&
            (phase == JellyfinCatalogPhase.MOVIES || movieComplete) &&
            (phase != JellyfinCatalogPhase.READY || seriesComplete)
    }

    fun encode(): String = listOf(
        VERSION,
        movieSessionId,
        seriesSessionId,
        phase.name,
        movieStartIndex,
        movieTotal ?: UNKNOWN_TOTAL,
        seriesStartIndex,
        seriesTotal ?: UNKNOWN_TOTAL
    ).joinToString("|")

    companion object {
        private const val VERSION = "jellyfin-v1"
        private const val UNKNOWN_TOTAL = -1

        fun decode(value: String?): JellyfinCatalogCheckpoint? = runCatching {
            val fields = value?.split('|') ?: return null
            if (fields.size != 8 || fields[0] != VERSION) return null
            JellyfinCatalogCheckpoint(
                movieSessionId = fields[1].toLong().takeIf { it > 0L } ?: return null,
                seriesSessionId = fields[2].toLong().takeIf { it > 0L } ?: return null,
                phase = JellyfinCatalogPhase.valueOf(fields[3]),
                movieStartIndex = fields[4].toInt().takeIf { it >= 0 } ?: return null,
                movieTotal = fields[5].toInt().takeIf { it >= 0 },
                seriesStartIndex = fields[6].toInt().takeIf { it >= 0 } ?: return null,
                seriesTotal = fields[7].toInt().takeIf { it >= 0 }
            )
        }.getOrNull()
    }
}

private fun validatePage(
    label: String,
    startIndex: Int,
    expectedTotal: Int,
    reportedTotal: Int,
    pageItemCount: Int,
    stagedCount: Int,
    maximumTotal: Int
) {
    if (reportedTotal != expectedTotal) {
        throw JellyfinPaginationException("Jellyfin $label total changed during pagination")
    }
    if (reportedTotal > maximumTotal) {
        throw JellyfinCatalogLimitException("Jellyfin $label catalog exceeds $maximumTotal items")
    }
    val nextStart = startIndex + pageItemCount
    if (
        pageItemCount < 0 ||
        nextStart > expectedTotal ||
        nextStart <= startIndex && nextStart < expectedTotal
    ) {
        throw JellyfinPaginationException("Jellyfin $label pagination did not advance")
    }
    if (stagedCount != nextStart) {
        throw JellyfinPaginationException("Jellyfin $label pagination repeated or omitted items")
    }
}
