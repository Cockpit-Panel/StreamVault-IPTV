package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.jellyfin.JellyfinCatalogLimitException
import com.streamvault.data.remote.jellyfin.JellyfinPaginationException
import org.junit.Test

class JellyfinCatalogCheckpointTest {

    @Test
    fun `checkpoint round trip preserves process death continuation`() {
        val beforeProcessDeath = JellyfinCatalogCheckpoint(
            movieSessionId = 101,
            seriesSessionId = 202,
            phase = JellyfinCatalogPhase.SERIES,
            movieStartIndex = 250,
            movieTotal = 250,
            seriesStartIndex = 100,
            seriesTotal = 275
        )

        val restored = JellyfinCatalogCheckpoint.decode(beforeProcessDeath.encode())

        assertThat(restored).isEqualTo(beforeProcessDeath)
        assertThat(restored!!.isConsistent(movieStageCount = 250, seriesStageCount = 100)).isTrue()
        assertThat(restored.seriesStartIndex).isEqualTo(100)
    }

    @Test
    fun `torn staged page invalidates checkpoint instead of skipping data`() {
        val checkpoint = JellyfinCatalogCheckpoint(
            movieSessionId = 101,
            seriesSessionId = 202,
            phase = JellyfinCatalogPhase.MOVIES,
            movieStartIndex = 200,
            movieTotal = 350
        )

        assertThat(checkpoint.isConsistent(movieStageCount = 199, seriesStageCount = 0)).isFalse()
        assertThat(checkpoint.isConsistent(movieStageCount = 201, seriesStageCount = 0)).isFalse()
    }

    @Test
    fun `ready checkpoint requires both complete staged catalogs`() {
        val ready = JellyfinCatalogCheckpoint(
            movieSessionId = 101,
            seriesSessionId = 202,
            phase = JellyfinCatalogPhase.READY,
            movieStartIndex = 2,
            movieTotal = 2,
            seriesStartIndex = 3,
            seriesTotal = 3
        )

        assertThat(ready.isConsistent(2, 3)).isTrue()
        assertThat(ready.copy(seriesStartIndex = 2).isConsistent(2, 2)).isFalse()
    }

    @Test
    fun `malformed or unsafe checkpoint is rejected`() {
        assertThat(JellyfinCatalogCheckpoint.decode(null)).isNull()
        assertThat(JellyfinCatalogCheckpoint.decode("wrong|1|2|MOVIES|0|-1|0|-1")).isNull()
        assertThat(JellyfinCatalogCheckpoint.decode("jellyfin-v1|-1|2|MOVIES|0|-1|0|-1")).isNull()
        assertThat(JellyfinCatalogCheckpoint.decode("jellyfin-v1|1|2|MOVIES|-1|-1|0|-1")).isNull()
    }

    @Test
    fun `server truncation advances through every explicit continuation`() {
        var checkpoint = JellyfinCatalogCheckpoint(101, 202, JellyfinCatalogPhase.MOVIES)

        checkpoint = checkpoint.afterMoviePage(reportedTotal = 150, pageItemCount = 40, stagedCount = 40)
        checkpoint = checkpoint.afterMoviePage(reportedTotal = 150, pageItemCount = 40, stagedCount = 80)
        checkpoint = checkpoint.afterMoviePage(reportedTotal = 150, pageItemCount = 40, stagedCount = 120)
        checkpoint = checkpoint.afterMoviePage(reportedTotal = 150, pageItemCount = 30, stagedCount = 150)

        assertThat(checkpoint.movieStartIndex).isEqualTo(150)
        assertThat(checkpoint.movieTotal).isEqualTo(150)
    }

    @Test
    fun `changing totals empty pages and repeated items are rejected`() {
        val checkpoint = JellyfinCatalogCheckpoint(
            movieSessionId = 101,
            seriesSessionId = 202,
            phase = JellyfinCatalogPhase.MOVIES,
            movieStartIndex = 100,
            movieTotal = 200
        )

        val changedTotal = runCatching { checkpoint.afterMoviePage(201, 100, 200) }.exceptionOrNull()
        val emptyPage = runCatching { checkpoint.afterMoviePage(200, 0, 100) }.exceptionOrNull()
        val repeatedItems = runCatching { checkpoint.afterMoviePage(200, 100, 150) }.exceptionOrNull()

        assertThat(changedTotal).isInstanceOf(JellyfinPaginationException::class.java)
        assertThat(emptyPage).isInstanceOf(JellyfinPaginationException::class.java)
        assertThat(repeatedItems).isInstanceOf(JellyfinPaginationException::class.java)
    }

    @Test
    fun `movie and series catalog ceilings are enforced before continuation`() {
        val movies = JellyfinCatalogCheckpoint(101, 202, JellyfinCatalogPhase.MOVIES)
        val series = movies.copy(phase = JellyfinCatalogPhase.SERIES, movieTotal = 0)

        val movieFailure = runCatching { movies.afterMoviePage(200_001, 100, 100) }.exceptionOrNull()
        val seriesFailure = runCatching { series.afterSeriesPage(100_001, 100, 100) }.exceptionOrNull()

        assertThat(movieFailure).isInstanceOf(JellyfinCatalogLimitException::class.java)
        assertThat(seriesFailure).isInstanceOf(JellyfinCatalogLimitException::class.java)
    }
}
