package com.streamvault.domain.usecase

import com.streamvault.domain.manager.ProviderSyncStateReader
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CatalogCompleteness
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.repository.SearchRepository
import com.streamvault.domain.repository.SearchRepositoryResult
import com.streamvault.domain.util.shouldRethrowDomainFlowFailure
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

enum class SearchContentScope {
    ALL,
    LIVE,
    MOVIES,
    SERIES
}

data class SearchContentResult(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val isPartialResult: Boolean = false,
    val catalogCompleteness: CatalogCompleteness = CatalogCompleteness.COMPLETE
)

class SearchContent @Inject constructor(
    private val searchRepository: SearchRepository,
    private val providerSyncStateReader: ProviderSyncStateReader
) {
    private companion object {
        const val SEARCH_RESPONSE_TIMEOUT_MS = 2_500L
    }

    private val logger = Logger.getLogger("SearchContent")

    operator fun invoke(
        providerId: Long,
        query: String,
        scope: SearchContentScope = SearchContentScope.ALL,
        maxResultsPerSection: Int = 120
    ): Flow<SearchContentResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) {
            return flowOf(SearchContentResult())
        }

        return combine(
            contentSearchFlow(providerId, normalizedQuery, scope, maxResultsPerSection),
            providerSyncStateReader.observeBackgroundIndexingActive(providerId)
        ) { searchResult, indexWorkActive ->
            val (result, searchDegraded) = searchResult
            val indexingActive = when (providerSyncStateReader.currentSyncState(providerId)) {
                is SyncState.Syncing,
                is SyncState.Partial -> true
                else -> false
            }
            SearchContentResult(
                channels = result.channels,
                movies = result.movies,
                series = result.series,
                isPartialResult = searchDegraded || indexingActive || indexWorkActive ||
                    result.catalogCompleteness != CatalogCompleteness.COMPLETE,
                catalogCompleteness = when {
                    searchDegraded -> CatalogCompleteness.PARTIAL
                    indexingActive || indexWorkActive -> CatalogCompleteness.INDEXING
                    else -> result.catalogCompleteness
                }
            )
        }
    }

    private fun contentSearchFlow(
        providerId: Long,
        query: String,
        scope: SearchContentScope,
        maxResultsPerSection: Int
    ): Flow<Pair<SearchRepositoryResult, Boolean>> =
        flow {
            val result = withTimeoutOrNull(SEARCH_RESPONSE_TIMEOUT_MS) {
                searchRepository.searchContent(
                    providerId = providerId,
                    query = query,
                    includeLive = scope == SearchContentScope.ALL || scope == SearchContentScope.LIVE,
                    includeMovies = scope == SearchContentScope.ALL || scope == SearchContentScope.MOVIES,
                    includeSeries = scope == SearchContentScope.ALL || scope == SearchContentScope.SERIES,
                    maxResultsPerSection = maxResultsPerSection
                ).first()
            }

            if (result == null) {
                logger.warning("Search timed out for provider $providerId and query '$query'")
                emit(SearchRepositoryResult() to true)
            } else {
                emit(result to false)
            }
        }
            .catch { error ->
                if (error.shouldRethrowDomainFlowFailure()) throw error
                logger.log(Level.WARNING, "Unified content search failed", error)
                emit(SearchRepositoryResult() to true)
            }
}
