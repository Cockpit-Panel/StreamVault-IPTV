package com.streamvault.data.local

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ProviderConfigRevisionEntity
import com.streamvault.data.local.entity.ProviderConfigRevisionState
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProviderConfigRevisionClockRecoveryTest {

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
    fun `process restart recovers future stale and invalid syncing revisions but not live owner`() = runTest {
        val providerDao = database.providerDao()
        val revisionDao = database.providerConfigRevisionDao()
        val now = 10_000L
        val staleBefore = 9_000L

        listOf(
            1L to 10_001L,
            2L to staleBefore,
            3L to 0L,
            4L to 9_999L
        ).forEach { (providerId, updatedAt) ->
            providerDao.insert(
                ProviderEntity(
                    id = providerId,
                    name = "Provider $providerId",
                    type = ProviderType.M3U,
                    serverUrl = "https://example.com/$providerId"
                )
            )
            revisionDao.upsert(
                ProviderConfigRevisionEntity(
                    providerId = providerId,
                    revision = 1L,
                    configJson = "{}",
                    state = ProviderConfigRevisionState.SYNCING,
                    createdAt = 1L,
                    updatedAt = updatedAt
                )
            )
        }

        val candidates = revisionDao.getRecoveryCandidates(now, staleBefore)

        assertThat(candidates.map { it.providerId }).containsExactly(1L, 2L, 3L)
        assertThat(candidates.map { it.providerId }).doesNotContain(4L)
    }

    @Test
    fun `newer edit fences older revision promotion and provider deletion removes stale work`() = runTest {
        val providerDao = database.providerDao()
        val revisionDao = database.providerConfigRevisionDao()
        providerDao.insert(
            ProviderEntity(
                id = 11L,
                name = "Provider",
                type = ProviderType.M3U,
                serverUrl = "https://example.com"
            )
        )
        revisionDao.upsert(
            ProviderConfigRevisionEntity(
                providerId = 11L,
                revision = 1L,
                configJson = "old",
                state = ProviderConfigRevisionState.SYNCING,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        revisionDao.upsert(
            ProviderConfigRevisionEntity(
                providerId = 11L,
                revision = 2L,
                configJson = "new",
                state = ProviderConfigRevisionState.PENDING,
                createdAt = 2L,
                updatedAt = 2L
            )
        )

        assertThat(revisionDao.markCommitted(11L, 1L, 3L)).isEqualTo(0)
        assertThat(revisionDao.getState(11L, 1L)).isEqualTo(ProviderConfigRevisionState.SYNCING)
        assertThat(revisionDao.claimForSync(11L, 2L, 4L)).isEqualTo(1)
        assertThat(revisionDao.markCommitted(11L, 2L, 5L)).isEqualTo(1)

        providerDao.delete(11L)
        assertThat(revisionDao.get(11L, 1L)).isNull()
        assertThat(revisionDao.get(11L, 2L)).isNull()
    }
}
