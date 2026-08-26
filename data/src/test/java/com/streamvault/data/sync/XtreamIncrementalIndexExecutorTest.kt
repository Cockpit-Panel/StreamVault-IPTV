package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock

class XtreamIncrementalIndexExecutorTest {
    @Test
    fun `successful full stream completes section and publishes metadata`() = runTest {
        val operations = FakeOperations(
            categories = listOf(category(1L)),
            streamMovieResult = Result.success(7)
        )
        val executor = XtreamIncrementalIndexExecutor(operations)

        executor.processSummary(
            provider = provider(),
            api = mock(),
            contentType = ContentType.MOVIE,
            maxCategories = null,
            onProgress = null
        )

        assertThat(operations.updates.last().state).isEqualTo("SUCCESS")
        assertThat(operations.updates.last().indexedRows).isEqualTo(7)
        assertThat(operations.metadataUpdates).containsExactly(ContentType.MOVIE, 7, "SUCCESS")
        assertThat(operations.scheduled).isEmpty()
    }

    @Test
    fun `failed full stream falls back to priority and cursor category slices`() = runTest {
        val operations = FakeOperations(
            categories = listOf(category(1L), category(2L)),
            job = XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED",
                priorityCategoryId = 2L
            ),
            streamMovieResult = Result.error("stream unavailable")
        )
        val executor = XtreamIncrementalIndexExecutor(operations)

        executor.processSummary(
            provider = provider(),
            api = mock(),
            contentType = ContentType.MOVIE,
            maxCategories = 2,
            onProgress = null
        )

        val finalUpdate = operations.updates.last()
        assertThat(finalUpdate.state).isEqualTo("QUEUED")
        assertThat(finalUpdate.completedCategories).isEqualTo(1)
        assertThat(finalUpdate.nextCategoryIndex).isEqualTo(1)
        assertThat(operations.fetchedCategoryIds).containsExactly(2L, 1L).inOrder()
        assertThat(operations.scheduled).containsExactly(ContentType.MOVIE)
    }

    @Test
    fun `failed category keeps cursor on failed work and does not skip later categories`() = runTest {
        val operations = FakeOperations(
            categories = listOf(category(1L), category(2L), category(3L)),
            job = XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED"
            ),
            streamMovieResult = Result.error("stream unavailable"),
            failureCategoryId = 2L
        )

        XtreamIncrementalIndexExecutor(operations).processSummary(
            provider = provider(),
            api = mock(),
            contentType = ContentType.MOVIE,
            maxCategories = 3,
            onProgress = null
        )

        assertThat(operations.fetchedCategoryIds).containsExactly(1L, 2L).inOrder()
        assertThat(operations.updates.last().nextCategoryIndex).isEqualTo(1)
        assertThat(operations.updates.last().state).isEqualTo("QUEUED")
        assertThat(operations.scheduled).containsExactly(ContentType.MOVIE)
    }

    @Test
    fun `cancellation after first category persists only completed cursor`() = runTest {
        val operations = FakeOperations(
            categories = listOf(category(1L), category(2L), category(3L)),
            job = XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "QUEUED"
            ),
            streamMovieResult = Result.error("stream unavailable"),
            cancelCategoryId = 2L
        )

        var cancelled = false
        try {
            XtreamIncrementalIndexExecutor(operations).processSummary(
                provider = provider(),
                api = mock(),
                contentType = ContentType.MOVIE,
                maxCategories = 3,
                onProgress = null
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertThat(cancelled).isTrue()
        assertThat(operations.updates.last().nextCategoryIndex).isEqualTo(1)
    }

    @Test
    fun `legacy partial job whose cursor passed failures restarts repair sweep`() = runTest {
        val operations = FakeOperations(
            categories = listOf(category(1L), category(2L), category(3L)),
            job = XtreamIndexJobEntity(
                providerId = 1L,
                section = ContentType.MOVIE.name,
                state = "PARTIAL",
                completedCategories = 2,
                nextCategoryIndex = 3,
                failedCategories = 1,
                indexedRows = 20
            ),
            streamMovieResult = Result.error("stream unavailable")
        )

        XtreamIncrementalIndexExecutor(operations).processSummary(
            provider = provider(),
            api = mock(),
            contentType = ContentType.MOVIE,
            maxCategories = 1,
            onProgress = null
        )

        assertThat(operations.fetchedCategoryIds).containsExactly(1L)
        assertThat(operations.updates.last().nextCategoryIndex).isEqualTo(1)
        assertThat(operations.updates.last().completedCategories).isEqualTo(1)
        assertThat(operations.updates.last().failedCategories).isEqualTo(0)
    }

    private class FakeOperations(
        private val categories: List<CategoryEntity>,
        private val job: XtreamIndexJobEntity? = null,
        private val streamMovieResult: Result<Int> = Result.success(0),
        private val failureCategoryId: Long? = null,
        private val cancelCategoryId: Long? = null
    ) : XtreamIncrementalIndexOperations {
        val updates = mutableListOf<CatalogIndexJobUpdate>()
        val fetchedCategoryIds = mutableListOf<Long>()
        val metadataUpdates = mutableListOf<Any>()
        val scheduled = mutableListOf<ContentType>()

        override suspend fun getCategories(providerId: Long, contentType: ContentType) = categories

        override suspend fun ensureCategoryShell(
            provider: Provider,
            api: XtreamProvider,
            contentType: ContentType,
            now: Long,
            onProgress: ((String) -> Unit)?
        ) = Unit

        override suspend fun getJob(providerId: Long, contentType: ContentType) = job

        override fun shouldRunSummary(job: XtreamIndexJobEntity?) = true

        override suspend fun fetchMovieCategory(
            provider: Provider,
            api: XtreamProvider,
            category: CategoryEntity
        ): TimedCategoryOutcome<Movie> {
            fetchedCategoryIds += category.categoryId
            if (category.categoryId == cancelCategoryId) {
                throw CancellationException("cancelled while fetching category")
            }
            if (category.categoryId == failureCategoryId) {
                return TimedCategoryOutcome(
                    category = category.asXtreamCategory(),
                    outcome = CategoryFetchOutcome.Failure(category.name, IOException("category failed")),
                    elapsedMs = 1L
                )
            }
            return TimedCategoryOutcome(
                category = category.asXtreamCategory(),
                outcome = CategoryFetchOutcome.Empty(category.name),
                elapsedMs = 1L
            )
        }

        override suspend fun fetchSeriesCategory(
            provider: Provider,
            api: XtreamProvider,
            category: CategoryEntity
        ): TimedCategoryOutcome<Series> = error("series not used")

        override suspend fun upsertMovieSummaryBatch(
            providerId: Long,
            movies: List<Movie>,
            indexedAt: Long
        ) = movies.size

        override suspend fun upsertSeriesSummaryBatch(
            providerId: Long,
            series: List<Series>,
            indexedAt: Long
        ) = series.size

        override suspend fun streamMovies(
            provider: Provider,
            api: XtreamProvider,
            adultCategoryIds: Set<Long>,
            onBatch: suspend (List<Movie>) -> Unit
        ) = streamMovieResult

        override suspend fun streamSeries(
            provider: Provider,
            api: XtreamProvider,
            adultCategoryIds: Set<Long>,
            onBatch: suspend (List<Series>) -> Unit
        ): Result<Int> = Result.error("series not used")

        override suspend fun upsertJob(update: CatalogIndexJobUpdate) {
            updates += update
        }

        override suspend fun updateSummaryMetadata(
            providerId: Long,
            contentType: ContentType,
            indexedRows: Int,
            state: String,
            now: Long
        ) {
            metadataUpdates += contentType
            metadataUpdates += indexedRows
            metadataUpdates += state
        }

        override fun scheduleIndex(providerId: Long, contentType: ContentType) {
            scheduled += contentType
        }

        override fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) = Unit

        override suspend fun restoreMovieWatchProgress(providerId: Long) = Unit

        override fun sanitize(error: Throwable?) = error?.message.orEmpty()

        override fun log(message: String) = Unit

        private fun CategoryEntity.asXtreamCategory() = XtreamCategory(
            categoryId = categoryId.toString(),
            categoryName = name,
            parentId = parentId?.toInt() ?: 0,
            isAdult = isAdult
        )
    }

    private fun category(id: Long) = CategoryEntity(
        providerId = 1L,
        categoryId = id,
        name = "Category $id",
        type = ContentType.MOVIE
    )

    private fun provider() = Provider(
        id = 1L,
        name = "Xtream",
        type = com.streamvault.domain.model.ProviderType.XTREAM_CODES,
        serverUrl = "https://example.com",
        username = "user",
        password = "password"
    )
}
