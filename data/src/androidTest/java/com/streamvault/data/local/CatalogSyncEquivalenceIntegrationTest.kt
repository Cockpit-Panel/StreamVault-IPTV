package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.sync.SyncCatalogStore
import com.streamvault.data.sync.SyncManagerM3uImporter
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Verifies that full and section-repair activation converge through the production Room DAOs. */
@RunWith(AndroidJUnit4::class)
class CatalogSyncEquivalenceIntegrationTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var store: SyncCatalogStore

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        store = SyncCatalogStore(
            channelDao = db.channelDao(),
            movieDao = db.movieDao(),
            seriesDao = db.seriesDao(),
            categoryDao = db.categoryDao(),
            catalogSyncDao = db.catalogSyncDao(),
            tmdbIdentityDao = db.tmdbIdentityDao(),
            transactionRunner = RoomDatabaseTransactionRunner(db)
        )
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun fullImportAndSectionRepairsConvergeToEquivalentActiveCatalogs() = runTest {
        val fullProviderId = insertProvider("Full")
        val repairProviderId = insertProvider("Repair")
        seedStaleCatalog(fullProviderId)
        seedStaleCatalog(repairProviderId)

        val importer = playlistImporter()
        importer.importPlaylist(provider(fullProviderId), onProgress = null)
        importer.importPlaylist(
            provider(repairProviderId),
            onProgress = null,
            includeLive = true,
            includeMovies = false
        )
        importer.importPlaylist(
            provider(repairProviderId),
            onProgress = null,
            includeLive = false,
            includeMovies = true
        )

        assertThat(channelSnapshot(fullProviderId)).isEqualTo(channelSnapshot(repairProviderId))
        assertThat(movieSnapshot(fullProviderId)).isEqualTo(movieSnapshot(repairProviderId))
        assertThat(categorySnapshot(fullProviderId)).isEqualTo(categorySnapshot(repairProviderId))
        assertThat(channelSnapshot(fullProviderId).map { it.streamId }).doesNotContain(999L)
        assertThat(movieSnapshot(fullProviderId).map { it.streamId }).doesNotContain(999L)
    }

    private suspend fun insertProvider(name: String): Long = db.providerDao().insert(
        ProviderEntity(name = name, type = ProviderType.M3U)
    )

    private suspend fun seedStaleCatalog(providerId: Long) {
        store.replaceLiveCatalog(
            providerId,
            categories = listOf(CategoryEntity(categoryId = 99L, name = "Stale", providerId = providerId)),
            channels = listOf(ChannelEntity(streamId = 999L, name = "Stale", providerId = providerId))
        )
        store.replaceMovieCatalog(
            providerId,
            categories = listOf(
                CategoryEntity(
                    categoryId = 99L,
                    name = "Stale",
                    type = ContentType.MOVIE,
                    providerId = providerId
                )
            ),
            movies = sequenceOf(MovieEntity(streamId = 999L, name = "Stale", providerId = providerId))
        )
    }

    private fun playlistImporter(): SyncManagerM3uImporter {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(PLAYLIST.toResponseBody("application/x-mpegURL".toMediaType()))
                .build()
        }.build()
        return SyncManagerM3uImporter(
            m3uParser = M3uParser(),
            okHttpClient = client,
            syncCatalogStore = store,
            retryTransient = { block -> block() },
            progress = { _, _, _ -> },
            emitProgress = { _, _ -> }
        )
    }

    private fun provider(providerId: Long) = LegacyProvider(
        id = providerId,
        name = "Provider $providerId",
        type = ProviderType.M3U,
        serverUrl = PLAYLIST_URL,
        m3uUrl = PLAYLIST_URL,
        m3uVodClassificationEnabled = true
    )

    private suspend fun channelSnapshot(providerId: Long) = db.channelDao().getByProviderSync(providerId)
        .sortedBy { it.streamId }
        .map { it.copy(id = 0L, providerId = 0L, syncFingerprint = "") }

    private suspend fun movieSnapshot(providerId: Long) = db.movieDao().getByProviderSync(providerId)
        .sortedBy { it.streamId }
        .map { it.copy(id = 0L, providerId = 0L, syncFingerprint = "") }

    private suspend fun categorySnapshot(providerId: Long) = ContentType.entries
        .flatMap { type -> db.categoryDao().getByProviderAndTypeSync(providerId, type.name) }
        .sortedWith(compareBy(CategoryEntity::type, CategoryEntity::categoryId))
        .map { it.copy(id = 0L, providerId = 0L, syncFingerprint = "") }

    private companion object {
        const val PLAYLIST_URL = "https://catalog.example.test/playlist.m3u"
        val PLAYLIST = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news" group-title="Live",News
            https://stream.example.test/live/10.m3u8
            #EXTINF:-1 tvg-id="sports" group-title="Live",Sports
            https://stream.example.test/live/20.m3u8
            #EXTINF:-1 group-title="Movies",Feature
            https://stream.example.test/movie/30.mp4
        """.trimIndent()
    }
}
