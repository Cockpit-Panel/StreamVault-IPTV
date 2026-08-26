package com.streamvault.data.mapper

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelQualityOption
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackWatchedStatus
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import org.junit.Test

/**
 * Unit tests for entity ↔ domain mapper extension functions in [EntityMappers].
 *
 * Validates that every field survives a round-trip through the data layer without
 * mutation, truncation, or silent default substitution.
 */
class EntityMappersTest {

    // ── Provider ──────────────────────────────────────────────────

    @Test
    fun `provider entity roundTrip preserves stable identity fields only`() {
        val legacy = Provider(
            id = 42L,
            name = "My Xtream Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "http://example.com:8080",
            username = "user123",
            password = "p@ssw0rd!",
            m3uUrl = "",
            epgUrl = "http://example.com/epg.xml",
            isActive = true,
            maxConnections = 3,
            expirationDate = 1_798_761_600_000L, // 2027-01-01 UTC as epoch ms
            apiVersion = "2.0.1",
            allowedOutputFormats = listOf("m3u8", "ts"),
            status = ProviderStatus.ACTIVE,
            lastSyncedAt = 1_700_000_000_000L,
            createdAt = 1_600_000_000_000L
        )

        val stableEntity = legacy.toEntity()
        val compatibilityView = stableEntity.toDomain()

        assertThat(compatibilityView.id).isEqualTo(legacy.id)
        assertThat(compatibilityView.name).isEqualTo(legacy.name)
        assertThat(compatibilityView.type).isEqualTo(legacy.type)
        assertThat(compatibilityView.isActive).isEqualTo(legacy.isActive)
        assertThat(compatibilityView.status).isEqualTo(legacy.status)
        assertThat(compatibilityView.lastSyncedAt).isEqualTo(legacy.lastSyncedAt)
        assertThat(compatibilityView.createdAt).isEqualTo(legacy.createdAt)
        assertThat(compatibilityView.serverUrl).isEmpty()
        assertThat(compatibilityView.username).isEmpty()
        assertThat(compatibilityView.password).isEmpty()
        assertThat(compatibilityView.maxConnections).isEqualTo(1)
        assertThat(compatibilityView.toEntity()).isEqualTo(stableEntity)
    }

    @Test
    fun `playbackHistory_unknownWatchedStatus_defaultsToInProgress`() {
        val entity = PlaybackHistoryEntity(
            contentId = 10L,
            contentType = ContentType.MOVIE,
            providerId = 1L,
            watchedStatus = "NOT_A_REAL_STATUS"
        )

        val domain = entity.toDomain()

        assertThat(domain.watchedStatus).isEqualTo(PlaybackWatchedStatus.IN_PROGRESS)
    }

    // ── Channel ───────────────────────────────────────────────────

    @Test
    fun `channel_nullableFields_handledCorrectly`() {
        val channel = Channel(
            id = 99L,
            name = "Test Channel",
            logoUrl = null,        // intentionally null
            groupTitle = "Live",
            categoryId = null,     // intentionally null
            categoryName = "Live",
            streamUrl = "http://stream.example.com/99.ts",
            epgChannelId = null,
            number = 0,
            catchUpSupported = false,
            catchUpDays = 0,
            qualityOptions = listOf(ChannelQualityOption(label = "1080p", height = 1080, url = "http://stream.example.com/99_1080.ts")),
            providerId = 1L
        )

        val roundTripped = channel.toEntity().toDomain()

        assertThat(roundTripped.id).isEqualTo(99L)
        assertThat(roundTripped.logoUrl).isNull()
        assertThat(roundTripped.categoryId).isNull()
        assertThat(roundTripped.epgChannelId).isNull()
        assertThat(roundTripped.name).isEqualTo("Test Channel")
        assertThat(roundTripped.streamUrl).isEqualTo("http://stream.example.com/99.ts")
        assertThat(roundTripped.qualityOptions).containsExactly(
            ChannelQualityOption(label = "1080p", height = 1080, url = "http://stream.example.com/99_1080.ts")
        )
        assertThat(roundTripped.alternativeStreams).containsExactly("http://stream.example.com/99_1080.ts")
    }

    @Test
    fun `channel_toEntity_thinsXtreamLiveRowsWithoutLosingPlaybackKeys`() {
        val directSource = "https://cdn.example.com/live/777/master.m3u8?token=abc"
        val channel = Channel(
            id = 99L,
            name = "Live Channel",
            categoryId = 12L,
            categoryName = "News",
            streamUrl = XtreamUrlFactory.buildInternalStreamUrl(
                providerId = 42L,
                kind = XtreamStreamKind.LIVE,
                streamId = 777L,
                containerExtension = "m3u8",
                directSource = directSource
            ),
            catchUpSource = "unused-live-source",
            providerId = 42L,
            qualityOptions = listOf(
                ChannelQualityOption(label = "HLS", url = "xtream://42/live/777?ext=m3u8"),
                ChannelQualityOption(label = "MPEG-TS", url = "xtream://42/live/777?ext=ts")
            ),
            streamId = 777L
        )

        val entity = channel.toEntity()
        val token = XtreamUrlFactory.parseInternalStreamUrl(entity.streamUrl)

        assertThat(entity.streamUrl).isEqualTo("xtream://42/live/777?ext=m3u8")
        assertThat(entity.streamUrl).doesNotContain("src=")
        assertThat(token?.providerId).isEqualTo(42L)
        assertThat(token?.streamId).isEqualTo(777L)
        assertThat(token?.containerExtension).isEqualTo("m3u8")
        assertThat(entity.streamId).isEqualTo(777L)
        assertThat(entity.categoryName).isEqualTo("News")
        assertThat(entity.catchUpSource).isNull()
        assertThat(entity.qualityOptionsJson).isNull()
    }

    // ── Movie ─────────────────────────────────────────────────────

    @Test
    fun `movie_watchProgress_preserved`() {
        val movie = Movie(
            id = 1001L,
            name = "Inception",
            posterUrl = "http://poster.example.com/inception.jpg",
            categoryId = 5L,
            categoryName = "Sci-Fi",
            streamUrl = "http://vod.example.com/inception.mp4",
            providerId = 2L,
            watchProgress = 4_800_000L,   // 80 minutes into the film
            lastWatchedAt = 1_700_000_000_000L
        )

        val roundTripped = movie.toEntity().toDomain()

        assertThat(roundTripped.id).isEqualTo(1001L)
        assertThat(roundTripped.name).isEqualTo("Inception")
        assertThat(roundTripped.watchProgress).isEqualTo(4_800_000L)
        assertThat(roundTripped.lastWatchedAt).isEqualTo(1_700_000_000_000L)
    }
}
