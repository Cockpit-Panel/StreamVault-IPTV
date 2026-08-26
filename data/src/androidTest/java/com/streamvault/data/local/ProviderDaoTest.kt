package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ProviderDaoTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var providerDao: ProviderDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        providerDao = db.providerDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun setActive_keepsExactlyOneActiveProviderAcrossRepeatedSwitches() = runTest {
        providerDao.insert(provider(id = 1L, name = "One", isActive = true))
        providerDao.insert(provider(id = 2L, name = "Two", isActive = false))
        providerDao.insert(provider(id = 3L, name = "Three", isActive = true))

        providerDao.setActive(2L)

        var providers = providerDao.getAllSync()
        assertThat(providers.filter(ProviderEntity::isActive).map(ProviderEntity::id)).containsExactly(2L)
        assertThat(providerDao.getActive().first()?.id).isEqualTo(2L)

        providerDao.setActive(3L)

        providers = providerDao.getAllSync()
        assertThat(providers.filter(ProviderEntity::isActive).map(ProviderEntity::id)).containsExactly(3L)
        assertThat(providerDao.getActive().first()?.id).isEqualTo(3L)
    }

    @Test
    fun insertAndUpdate_normalizeActiveProviderUniqueness() = runTest {
        providerDao.insert(provider(id = 1L, name = "One", isActive = true))
        providerDao.insert(provider(id = 2L, name = "Two", isActive = true))

        var providers = providerDao.getAllSync()
        assertThat(providers.filter(ProviderEntity::isActive).map(ProviderEntity::id)).containsExactly(2L)

        providerDao.update(provider(id = 1L, name = "One", isActive = true))

        providers = providerDao.getAllSync()
        assertThat(providers.filter(ProviderEntity::isActive).map(ProviderEntity::id)).containsExactly(1L)
        assertThat(providerDao.getActive().first()?.id).isEqualTo(1L)
    }

    private fun provider(id: Long, name: String, isActive: Boolean) = ProviderEntity(
        id = id,
        name = name,
        type = ProviderType.M3U,
        isActive = isActive
    )
}
