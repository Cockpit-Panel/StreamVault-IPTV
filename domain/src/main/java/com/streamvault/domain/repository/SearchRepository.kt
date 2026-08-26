package com.streamvault.domain.repository

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CatalogCompleteness
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import kotlinx.coroutines.flow.Flow

data class SearchRepositoryResult(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val catalogCompleteness: CatalogCompleteness = CatalogCompleteness.COMPLETE
)

interface SearchRepository {
    fun searchContent(
        providerId: Long,
        query: String,
        includeLive: Boolean,
        includeMovies: Boolean,
        includeSeries: Boolean,
        maxResultsPerSection: Int
    ): Flow<SearchRepositoryResult>

    fun searchChannels(providerId: Long, query: String): Flow<List<Channel>>

    fun searchMovies(providerId: Long, query: String): Flow<List<Movie>>

    fun searchSeries(providerId: Long, query: String): Flow<List<Series>>
}
