package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BrowseQueryPlanTest {
    private lateinit var db: StreamVaultDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun explainQueryPlan_usesMovieBrowseAndPlaybackHistoryIndexes() = runTest {
        seedProviderCatalog()

        val browsePlan = explainQueryPlan(
            "SELECT * FROM movies WHERE provider_id = 1 ORDER BY name ASC, id ASC LIMIT 25"
        )
        assertUsesIndex(browsePlan, "index_movies_provider_id_name_id")
        assertNoTempSort(browsePlan)

        val inProgressPlan = explainQueryPlan(
            """
            SELECT movies.* FROM movies
            WHERE movies.provider_id = 1
              AND EXISTS (
                  SELECT 1 FROM playback_history
                  WHERE playback_history.provider_id = movies.provider_id
                    AND playback_history.content_type = 'MOVIE'
                    AND playback_history.content_id = movies.id
                    AND playback_history.resume_position_ms > 0
                    AND (
                        playback_history.total_duration_ms <= 0
                        OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                    )
              )
            ORDER BY movies.name ASC
            LIMIT 25 OFFSET 0
            """.trimIndent()
        )
        assertUsesIndex(inProgressPlan, "index_movies_provider_id_name_id")
        assertUsesIndex(inProgressPlan, "index_playback_history_provider_id_content_type_content_id")
        assertNoTempSort(inProgressPlan)
    }

    @Test
    fun explainQueryPlan_usesSeriesLastModifiedBrowseIndex() = runTest {
        seedProviderCatalog()

        val plan = explainQueryPlan(
            "SELECT * FROM series WHERE provider_id = 1 ORDER BY last_modified DESC, name ASC, id ASC LIMIT 25"
        )

        assertUsesIndex(plan, "index_series_provider_id_last_modified_name_id")
        assertNoTempSort(plan)
    }

    private suspend fun seedProviderCatalog() {
        db.providerDao().insert(
            ProviderEntity(
                id = 1L,
                name = "Provider",
                type = ProviderType.XTREAM_CODES
            )
        )
        db.movieDao().insertAll(
            List(40) { index ->
                MovieEntity(
                    id = index + 1L,
                    streamId = 10_000L + index,
                    name = "Movie %02d".format(index),
                    streamUrl = "https://provider.example.com/movies/$index.mp4",
                    providerId = 1L,
                    rating = (index % 5).toFloat(),
                    addedAt = 1_000L + index,
                    releaseDate = "2026-01-%02d".format((index % 28) + 1)
                )
            }
        )
        db.seriesDao().insertAll(
            List(40) { index ->
                SeriesEntity(
                    id = index + 1L,
                    seriesId = 20_000L + index,
                    name = "Series %02d".format(index),
                    providerId = 1L,
                    rating = (index % 5).toFloat(),
                    lastModified = 2_000L + index
                )
            }
        )
        db.playbackHistoryDao().insertOrUpdate(
            PlaybackHistoryEntity(
                contentId = 1L,
                contentType = ContentType.MOVIE,
                providerId = 1L,
                title = "Movie 00",
                streamUrl = "https://provider.example.com/movies/0.mp4",
                resumePositionMs = 60_000L,
                totalDurationMs = 120_000L,
                lastWatchedAt = 5_000L,
                watchCount = 1
            )
        )
    }

    private fun explainQueryPlan(sql: String): List<String> {
        val plan = mutableListOf<String>()
        db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            while (cursor.moveToNext()) {
                plan += cursor.getString(3)
            }
        }
        return plan
    }

    private fun assertUsesIndex(plan: List<String>, indexName: String) {
        assertThat(plan.any { it.contains(indexName) }).isTrue()
    }

    private fun assertNoTempSort(plan: List<String>) {
        assertThat(plan.none { it.contains("USE TEMP B-TREE FOR ORDER BY") }).isTrue()
    }
}
