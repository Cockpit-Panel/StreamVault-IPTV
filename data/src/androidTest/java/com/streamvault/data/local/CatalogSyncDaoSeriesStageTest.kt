package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.SeriesImportStageEntity
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SeriesCatalogOrigin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CatalogSyncDaoSeriesStageTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var providerDao: ProviderDao
    private lateinit var seriesDao: SeriesDao
    private lateinit var episodeDao: EpisodeDao
    private lateinit var catalogSyncDao: CatalogSyncDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        providerDao = db.providerDao()
        seriesDao = db.seriesDao()
        episodeDao = db.episodeDao()
        catalogSyncDao = db.catalogSyncDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertMissingSeriesFromStage_preservesProviderSeriesId() = runTest {
        providerDao.insert(provider(1L))
        catalogSyncDao.insertSeriesStages(
            listOf(
                SeriesImportStageEntity(
                    sessionId = 10L,
                    providerId = 1L,
                    seriesId = 256103980L,
                    providerSeriesId = "55000:55000",
                    providerSeriesKey = "55000:55000",
                    name = "Composite Series",
                    syncFingerprint = "fp-new"
                )
            )
        )

        catalogSyncDao.insertMissingSeriesFromStage(providerId = 1L, sessionId = 10L)

        val inserted = seriesDao.getByProviderSeriesId(1L, "55000:55000")

        assertThat(inserted).isNotNull()
        assertThat(inserted?.providerSeriesId).isEqualTo("55000:55000")
        assertThat(inserted?.seriesId).isEqualTo(256103980L)
    }

    @Test
    fun updateChangedSeriesFromStage_backfillsProviderSeriesIdForExistingSeries() = runTest {
        providerDao.insert(provider(1L))
        seriesDao.insertAll(
            listOf(
                SeriesEntity(
                    seriesId = 256103980L,
                    providerSeriesId = null,
                    name = "Composite Series",
                    providerId = 1L,
                    syncFingerprint = "old-fingerprint"
                )
            )
        )
        catalogSyncDao.insertSeriesStages(
            listOf(
                SeriesImportStageEntity(
                    sessionId = 11L,
                    providerId = 1L,
                    seriesId = 256103980L,
                    providerSeriesId = "55000:55000",
                    providerSeriesKey = "55000:55000",
                    name = "Composite Series",
                    syncFingerprint = "new-fingerprint"
                )
            )
        )

        catalogSyncDao.updateChangedSeriesFromStage(providerId = 1L, sessionId = 11L)

        val updated = seriesDao.getBySeriesId(1L, 256103980L)

        assertThat(updated?.providerSeriesId).isEqualTo("55000:55000")
        assertThat(updated?.syncFingerprint).isEqualTo("new-fingerprint")
    }

    @Test
    fun upsertCategoryPage_preservesHydratedDetailsAndEpisodes() = runTest {
        providerDao.insert(provider(1L))
        seriesDao.insertAll(
            listOf(
                SeriesEntity(
                    seriesId = 331741L,
                    providerSeriesId = "331741",
                    name = "The Apartment Job",
                    plot = "Hydrated plot",
                    providerId = 1L,
                    cacheState = "DETAIL_HYDRATED",
                    detailHydratedAt = 1234L,
                    catalogOrigin = SeriesCatalogOrigin.VOD_DERIVED,
                    episodePlaybackTemplateUrl = "stalker://1/episode/331741?cmd=parent"
                )
            )
        )
        val persisted = requireNotNull(seriesDao.getBySeriesId(1L, 331741L))
        episodeDao.insertAll(
            listOf(
                EpisodeEntity(
                    episodeId = 2535382L,
                    title = "Episode 8",
                    episodeNumber = 8,
                    seasonNumber = 1,
                    streamUrl = "stalker://1/episode/2535382?series=8",
                    seriesId = persisted.id,
                    providerId = 1L
                )
            )
        )

        seriesDao.upsertCategoryPage(
            providerId = 1L,
            series = listOf(
                SeriesEntity(
                    seriesId = 331741L,
                    providerSeriesId = "331741",
                    name = "The Apartment Job (catalog refresh)",
                    providerId = 1L,
                    cacheState = "SUMMARY_ONLY",
                    detailHydratedAt = 0L,
                    catalogOrigin = SeriesCatalogOrigin.VOD_DERIVED
                )
            )
        )

        val refreshed = requireNotNull(seriesDao.getBySeriesId(1L, 331741L))
        assertThat(refreshed.id).isEqualTo(persisted.id)
        assertThat(refreshed.name).isEqualTo("The Apartment Job (catalog refresh)")
        assertThat(refreshed.plot).isEqualTo("Hydrated plot")
        assertThat(refreshed.cacheState).isEqualTo("DETAIL_HYDRATED")
        assertThat(refreshed.detailHydratedAt).isEqualTo(1234L)
        assertThat(episodeDao.getBySeriesSync(refreshed.id).map { it.episodeId })
            .containsExactly(2535382L)
    }

    private fun provider(id: Long) = ProviderEntity(
        id = id,
        name = "Provider $id",
        type = ProviderType.STALKER_PORTAL
    )
}
