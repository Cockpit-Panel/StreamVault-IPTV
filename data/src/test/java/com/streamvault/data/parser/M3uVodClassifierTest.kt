package com.streamvault.data.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uVodClassifierTest {

    private val classifier = M3uVodClassifier()

    @Test
    fun `file extensions ignore query fragment case and percent encoding`() {
        val urls = listOf(
            "https://cdn.example.com/film.MP4?token=abc",
            "https://cdn.example.com/show.mkv#preview",
            "https://cdn.example.com/archive.avi?token=abc#preview",
            "https://cdn.example.com/encoded%2Emp4?token=abc"
        )

        urls.forEach { url ->
            val result = classifier.classify(entry(url = url))
            assertThat(result.kind).isEqualTo(M3uMediaKind.VOD)
            assertThat(result.evidence).isEqualTo(M3uVodEvidence.FILE_EXTENSION)
            assertThat(result.confidence).isEqualTo(1.0f)
        }
    }

    @Test
    fun `extensionless and encoded movie paths classify as vod`() {
        val urls = listOf(
            "https://provider.example.com/movie/user/pass/12345",
            "https://provider.example.com/%6Dovie/user/pass/67890",
            "https://provider.example.com/vod/13579?token=abc"
        )

        urls.forEach { url ->
            val result = classifier.classify(entry(url = url))
            assertThat(result.kind).isEqualTo(M3uMediaKind.VOD)
            assertThat(result.evidence).isEqualTo(M3uVodEvidence.PATH_SEGMENT)
        }
    }

    @Test
    fun `localized group keywords classify extensionless entries as vod`() {
        listOf("Películas", "Filmes", "Фильмы", "电影", "映画", "أفلام", "סרטים").forEach { group ->
            val result = classifier.classify(
                entry(url = "https://provider.example.com/item/123", group = group)
            )
            assertThat(result.kind).isEqualTo(M3uMediaKind.VOD)
            assertThat(result.evidence).isEqualTo(M3uVodEvidence.GROUP_KEYWORD)
        }
    }

    @Test
    fun `name keyword classifies an otherwise ambiguous extensionless entry`() {
        val result = classifier.classify(
            entry(
                url = "https://provider.example.com/item/123",
                group = "General",
                name = "Classic Cinema"
            )
        )

        assertThat(result.kind).isEqualTo(M3uMediaKind.VOD)
        assertThat(result.evidence).isEqualTo(M3uVodEvidence.NAME_KEYWORD)
        assertThat(result.confidence).isEqualTo(0.55f)
    }

    @Test
    fun `strong live evidence beats a misleading movie label`() {
        val hls = classifier.classify(
            entry(url = "https://live.example.com/news.m3u8", group = "Movies Live")
        )
        val transportStream = classifier.classify(
            entry(url = "https://live.example.com/movie-news.ts", group = "Film Channel")
        )

        assertThat(hls.kind).isEqualTo(M3uMediaKind.LIVE)
        assertThat(hls.evidence).isEqualTo(M3uVodEvidence.LIVE_STREAM_EXTENSION)
        assertThat(transportStream.kind).isEqualTo(M3uMediaKind.LIVE)
        assertThat(transportStream.evidence).isEqualTo(M3uVodEvidence.LIVE_STREAM_EXTENSION)
    }

    @Test
    fun `live text hint beats weak group and name heuristics`() {
        val result = classifier.classify(
            entry(
                url = "https://provider.example.com/item/123",
                group = "Live Movies",
                name = "Film Channel"
            )
        )

        assertThat(result.kind).isEqualTo(M3uMediaKind.LIVE)
        assertThat(result.evidence).isEqualTo(M3uVodEvidence.LIVE_TEXT_HINT)
    }

    @Test
    fun `rules are configurable and report the matching evidence`() {
        val customClassifier = M3uVodClassifier(
            M3uVodRules(vodKeywords = setOf("ciné club"))
        )

        val result = customClassifier.classify(
            entry(url = "https://provider.example.com/item/123", group = "Ciné Club Français")
        )

        assertThat(result.kind).isEqualTo(M3uMediaKind.VOD)
        assertThat(result.evidence).isEqualTo(M3uVodEvidence.GROUP_KEYWORD)
        assertThat(result.confidence).isEqualTo(0.7f)
    }

    @Test
    fun `explicit override wins and repeated classification is stable`() {
        val movieEntry = entry(url = "https://cdn.example.com/film.mp4", group = "Movies")

        val overridden = classifier.classify(movieEntry, override = M3uMediaKind.LIVE)
        val first = classifier.classify(movieEntry)
        val second = classifier.classify(movieEntry)

        assertThat(overridden.kind).isEqualTo(M3uMediaKind.LIVE)
        assertThat(overridden.evidence).isEqualTo(M3uVodEvidence.EXPLICIT_OVERRIDE)
        assertThat(first).isEqualTo(second)
    }

    private fun entry(
        url: String,
        group: String = "General",
        name: String = "Item"
    ) = M3uParser.M3uEntry(
        name = name,
        groupTitle = group,
        tvgId = null,
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
