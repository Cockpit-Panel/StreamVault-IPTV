package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.MovieDetailPresentationHint
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.MovieRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class GetRecommendationsTest {

    @Test
    fun returnsRepositoryRecommendationsWhenAvailable() = runTest {
        val useCase = GetRecommendations(
            movieRepository = FakeMovieRepository(
                recommendations = listOf(
                    Movie(id = 1L, name = "Recommended"),
                    Movie(id = 2L, name = "Recommended 2")
                ),
                topRated = listOf(Movie(id = 3L, name = "Top Rated"))
            )
        )

        val result = useCase(providerId = 7L, limit = 2).first()

        assertThat(result).isInstanceOf(RecommendationsResult.Success::class.java)
        assertThat((result as RecommendationsResult.Success).movies.map(Movie::id))
            .containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun fallsBackToTopRatedWhenRecommendationsAreEmpty() = runTest {
        val useCase = GetRecommendations(
            movieRepository = FakeMovieRepository(
                recommendations = emptyList(),
                topRated = listOf(
                    Movie(id = 10L, name = "Top Rated 1"),
                    Movie(id = 11L, name = "Top Rated 2")
                )
            )
        )

        val result = useCase(providerId = 7L, limit = 2).first()

        assertThat(result).isInstanceOf(RecommendationsResult.Success::class.java)
        assertThat((result as RecommendationsResult.Success).movies.map(Movie::id))
            .containsExactly(10L, 11L).inOrder()
    }

    @Test
    fun rethrows_non_io_upstream_failures() = runTest {
        val expected = IllegalStateException("recommendations query failed")
        val useCase = GetRecommendations(
            movieRepository = FakeMovieRepository(
                recommendations = emptyList(),
                topRated = emptyList(),
                recommendationsFlow = flow { throw expected }
            )
        )

        val thrown = try {
            useCase(providerId = 7L, limit = 2).first()
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown?.message).isEqualTo(expected.message)
    }

    @Test
    fun returns_degraded_on_io_failure() = runTest {
        val useCase = GetRecommendations(
            movieRepository = FakeMovieRepository(
                recommendations = emptyList(),
                topRated = emptyList(),
                recommendationsFlow = flow { throw IOException("network failure") }
            )
        )

        val result = useCase(providerId = 7L, limit = 2).first()

        assertThat(result).isEqualTo(RecommendationsResult.Degraded)
    }

    private class FakeMovieRepository(
        private val recommendations: List<Movie>,
        private val topRated: List<Movie>,
        private val recommendationsFlow: Flow<List<Movie>>? = null,
        private val topRatedFlow: Flow<List<Movie>>? = null
    ) : MovieRepository {
        override fun getRecommendations(providerId: Long, limit: Int): Flow<List<Movie>> = recommendationsFlow ?: flowOf(recommendations.take(limit))
        override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Movie>> = topRatedFlow ?: flowOf(topRated.take(limit))

        override fun getMovies(providerId: Long): Flow<List<Movie>> = unsupported()
        override fun getMoviesByCategory(providerId: Long, categoryId: Long): Flow<List<Movie>> = unsupported()
        override fun getMoviesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<Movie>> = unsupported()
        override fun getMoviesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Movie>> = unsupported()
        override fun getCategoryPreviewRows(providerId: Long, categoryIds: List<Long>, limitPerCategory: Int): Flow<Map<Long?, List<Movie>>> = unsupported()
        override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Movie>> = unsupported()
        override fun getRelatedContent(providerId: Long, movieId: Long, limit: Int): Flow<List<Movie>> = unsupported()
        override fun getMoviesByIds(ids: List<Long>): Flow<List<Movie>> = unsupported()
        override fun getCategories(providerId: Long): Flow<List<Category>> = unsupported()
        override fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>> = unsupported()
        override fun getLibraryCount(providerId: Long): Flow<Int> = unsupported()
        override fun browseMovies(query: LibraryBrowseQuery): Flow<PagedResult<Movie>> = unsupported()
        override fun searchMovies(providerId: Long, query: String): Flow<List<Movie>> = unsupported()
        override suspend fun getMovie(movieId: Long): Movie? = error("Not used in test")
        override suspend fun getMovieDetails(
            providerId: Long,
            movieId: Long,
            knownPresentation: MovieDetailPresentationHint?
        ): Result<Movie> = error("Not used in test")
        override suspend fun getStreamInfo(movie: Movie): Result<StreamInfo> = error("Not used in test")
        override suspend fun refreshMovies(providerId: Long): Result<Unit> = error("Not used in test")

        private fun <T> unsupported(): Flow<T> = error("Not used in test")
    }
}
