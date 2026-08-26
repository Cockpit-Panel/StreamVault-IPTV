package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VodCatalogTest {
    @Test
    fun categoryKind_isDerivedFromAllTypedItems() {
        val movie = VodCatalogItem.MovieItem(Movie(id = 1, name = "Movie", streamUrl = "movie"))
        val series = VodCatalogItem.SeriesItem(Series(id = 2, name = "Series"))

        assertThat(VodCategoryKind.from(emptyList())).isEqualTo(VodCategoryKind.UNKNOWN)
        assertThat(VodCategoryKind.from(listOf(movie))).isEqualTo(VodCategoryKind.MOVIES)
        assertThat(VodCategoryKind.from(listOf(series))).isEqualTo(VodCategoryKind.SERIES)
        assertThat(VodCategoryKind.from(listOf(movie, series))).isEqualTo(VodCategoryKind.MIXED)
    }

    @Test
    fun movieAndSeriesStableIds_doNotCollide() {
        val movie = VodCatalogItem.MovieItem(Movie(id = 7, name = "Shared", streamUrl = "movie"))
        val series = VodCatalogItem.SeriesItem(Series(id = 7, name = "Shared"))

        assertThat(movie.stableId).isNotEqualTo(series.stableId)
    }

    @Test
    fun categoryLoadMode_defaultsUnknownValuesToPaged() {
        assertThat(VodCategoryLoadMode.fromStorage(null)).isEqualTo(VodCategoryLoadMode.PAGED)
        assertThat(VodCategoryLoadMode.fromStorage("future-mode")).isEqualTo(VodCategoryLoadMode.PAGED)
        assertThat(VodCategoryLoadMode.fromStorage("complete_on_open"))
            .isEqualTo(VodCategoryLoadMode.COMPLETE_ON_OPEN)
    }
}
