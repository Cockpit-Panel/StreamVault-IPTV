package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.VirtualGroupEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class FavoriteDaoTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var providerDao: ProviderDao
    private lateinit var favoriteDao: FavoriteDao
    private lateinit var virtualGroupDao: VirtualGroupDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        providerDao = db.providerDao()
        favoriteDao = db.favoriteDao()
        virtualGroupDao = db.virtualGroupDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun getByGroup_hidesFavoritesWhoseProviderOrTypeNoLongerMatchesTheGroup() = runTest {
        providerDao.insert(provider(id = 1L, name = "Live Provider"))
        providerDao.insert(provider(id = 2L, name = "Movie Provider"))

        val liveGroupId = virtualGroupDao.insert(
            VirtualGroupEntity(
                providerId = 1L,
                name = "Live Group",
                contentType = ContentType.LIVE
            )
        )

        favoriteDao.insert(
            FavoriteEntity(
                providerId = 1L,
                contentId = 100L,
                contentType = ContentType.LIVE,
                position = 0,
                groupId = liveGroupId
            )
        )
        favoriteDao.insert(
            FavoriteEntity(
                providerId = 2L,
                contentId = 200L,
                contentType = ContentType.LIVE,
                position = 1,
                groupId = liveGroupId
            )
        )
        favoriteDao.insert(
            FavoriteEntity(
                providerId = 1L,
                contentId = 300L,
                contentType = ContentType.MOVIE,
                position = 2,
                groupId = liveGroupId
            )
        )

        val favorites = favoriteDao.getByGroup(liveGroupId).first()

        assertThat(favorites.map(FavoriteEntity::contentId)).containsExactly(100L)
    }

    @Test
    fun insert_replacesDuplicateGlobalFavoriteInsteadOfCreatingTwoRows() = runTest {
        providerDao.insert(provider(id = 1L, name = "Provider"))

        favoriteDao.insert(
            FavoriteEntity(
                providerId = 1L,
                contentId = 100L,
                contentType = ContentType.MOVIE,
                position = 0,
                groupId = null,
                addedAt = 10L
            )
        )
        favoriteDao.insert(
            FavoriteEntity(
                providerId = 1L,
                contentId = 100L,
                contentType = ContentType.MOVIE,
                position = 99,
                groupId = null,
                addedAt = 20L
            )
        )

        val favorites = favoriteDao.getGlobalByType(1L, ContentType.MOVIE.name).first()

        assertThat(favorites).hasSize(1)
        assertThat(favorites.single().contentId).isEqualTo(100L)
    }

    @Test
    fun insert_rejectsFavoriteGroupOwnedByAnotherProvider() = runTest {
        providerDao.insert(provider(id = 1L, name = "Provider"))
        providerDao.insert(provider(id = 2L, name = "Other Provider"))

        val foreignGroupId = virtualGroupDao.insert(
            VirtualGroupEntity(
                providerId = 2L,
                name = "Foreign Group",
                contentType = ContentType.LIVE
            )
        )

        val failure = try {
            favoriteDao.insert(
                FavoriteEntity(
                    providerId = 1L,
                    contentId = 100L,
                    contentType = ContentType.LIVE,
                    position = 0,
                    groupId = foreignGroupId
                )
            )
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        assertThat(failure).isNotNull()
        assertThat(failure!!.message).contains("belongs to provider 2, not 1")
        assertThat(favoriteDao.getAllByType(1L, ContentType.LIVE.name).first()).isEmpty()
    }

    @Test
    fun updateGroup_rejectsFavoriteGroupWithWrongContentType() = runTest {
        providerDao.insert(provider(id = 1L, name = "Provider"))
        val movieGroupId = virtualGroupDao.insert(
            VirtualGroupEntity(
                providerId = 1L,
                name = "Movie Group",
                contentType = ContentType.MOVIE
            )
        )
        favoriteDao.insert(
            FavoriteEntity(
                id = 5L,
                providerId = 1L,
                contentId = 100L,
                contentType = ContentType.LIVE,
                position = 0,
                groupId = null
            )
        )

        val failure = try {
            favoriteDao.updateGroup(5L, movieGroupId)
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        assertThat(failure).isNotNull()
        assertThat(failure!!.message).contains("accepts MOVIE, not LIVE")
        assertThat(favoriteDao.get(1L, 100L, ContentType.LIVE.name, null)?.groupId).isNull()
    }

    private fun provider(id: Long, name: String) = ProviderEntity(
        id = id,
        name = name,
        type = ProviderType.M3U
    )
}
