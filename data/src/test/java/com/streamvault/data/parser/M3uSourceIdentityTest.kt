package com.streamvault.data.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uSourceIdentityTest {

    @Test
    fun `tvg id is the stable identity across title and URL refresh changes`() {
        val first = entry(
            tvgId = "movie-42",
            name = "Movie 42",
            url = "https://cdn.example.com/movie/42?token=old"
        )
        val refreshed = entry(
            tvgId = "MOVIE-42",
            name = "Movie 42 (HD)",
            url = "https://cdn.example.com/movie/42?token=new"
        )

        assertThat(M3uSourceIdentity.fromEntry(7L, first))
            .isEqualTo(M3uSourceIdentity.fromEntry(7L, refreshed))
        assertThat(M3uSourceIdentity.stableLongId(7L, first))
            .isEqualTo(M3uSourceIdentity.stableLongId(7L, refreshed))
    }

    @Test
    fun `different providers do not share source identities`() {
        val item = entry(tvgId = "same-id", name = "Same title", url = "https://cdn.example.com/item")

        assertThat(M3uSourceIdentity.fromEntry(7L, item))
            .isNotEqualTo(M3uSourceIdentity.fromEntry(8L, item))
    }

    private fun entry(tvgId: String?, name: String, url: String) = M3uParser.M3uEntry(
        name = name,
        groupTitle = "General",
        tvgId = tvgId,
        tvgName = null,
        tvgLogo = null,
        tvgChno = null,
        tvgLanguage = null,
        tvgCountry = null,
        catchUp = null,
        catchUpDays = null,
        catchUpSource = null,
        timeshift = null,
        url = url
    )
}
