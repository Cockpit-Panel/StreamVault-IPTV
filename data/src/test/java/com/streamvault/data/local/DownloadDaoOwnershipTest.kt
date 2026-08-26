package com.streamvault.data.local

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.DownloadEntity
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DownloadDaoOwnershipTest {

    private lateinit var database: StreamVaultDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            StreamVaultDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `concurrent schedulers grant exactly one durable owner`() = runTest {
        val dao = database.downloadDao()
        dao.insert(download())

        val claims = withContext(Dispatchers.Default) {
            listOf("owner-a", "owner-b").map { owner ->
                async { dao.claimForDownload("download-1", owner, 1234L) }
            }.awaitAll()
        }

        assertThat(claims.sum()).isEqualTo(1)
        val claimed = dao.getByIdOnce("download-1")!!
        assertThat(claimed.status).isEqualTo(DownloadStatus.DOWNLOADING)
        assertThat(claimed.ownerId).isAnyOf("owner-a", "owner-b")
        assertThat(claimed.ownerEpoch).isEqualTo(1L)
        assertThat(claimed.heartbeatAt).isEqualTo(1234L)
    }

    @Test
    fun `restart sees prior process owner as orphan but not current owner`() = runTest {
        val dao = database.downloadDao()
        dao.insert(download())
        assertThat(dao.claimForDownload("download-1", "old-process", 1234L)).isEqualTo(1)

        assertThat(dao.getOrphanedDownloading("new-process").map { it.id })
            .containsExactly("download-1")
        assertThat(dao.getOrphanedDownloading("old-process")).isEmpty()
    }

    private fun download() = DownloadEntity(
        id = "download-1",
        providerId = 1L,
        contentType = DownloadContentType.MOVIE,
        contentId = 2L,
        contentName = "Movie",
        streamUrl = "https://example.com/movie.mp4"
    )
}
