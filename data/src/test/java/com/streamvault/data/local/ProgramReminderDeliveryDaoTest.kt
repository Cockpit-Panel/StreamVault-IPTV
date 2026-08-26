package com.streamvault.data.local

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ProgramReminderEntity
import com.streamvault.domain.model.ProgramReminderDeliveryState
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
class ProgramReminderDeliveryDaoTest {

    private lateinit var database: StreamVaultDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            StreamVaultDatabase::class.java
        ).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `concurrent alarm deliveries grant exactly one attempt token`() = runTest {
        val dao = database.programReminderDao()
        dao.insert(
            ProgramReminderEntity(
                id = 42L,
                providerId = 7L,
                channelId = "bbc1",
                channelName = "BBC One",
                programTitle = "World News",
                programStartTime = 10_000L,
                remindAt = 5_000L
            )
        )

        val claims = withContext(Dispatchers.Default) {
            listOf("attempt-a", "attempt-b").map { token ->
                async { dao.claimDelivery(42L, token, 5_000L) }
            }.awaitAll()
        }

        assertThat(claims.sum()).isEqualTo(1)
        val claimed = dao.getById(42L)!!
        assertThat(claimed.deliveryState).isEqualTo(ProgramReminderDeliveryState.DELIVERING)
        assertThat(claimed.deliveryAttemptToken).isAnyOf("attempt-a", "attempt-b")
        assertThat(claimed.deliveryAttemptCount).isEqualTo(1)
        assertThat(claimed.exactAlarmArmed).isFalse()
    }

    @Test
    fun `stale completion token cannot overwrite a newer delivery attempt`() = runTest {
        val dao = database.programReminderDao()
        dao.insert(
            ProgramReminderEntity(
                id = 42L,
                providerId = 7L,
                channelId = "bbc1",
                channelName = "BBC One",
                programTitle = "World News",
                programStartTime = 10_000L,
                remindAt = 5_000L
            )
        )
        assertThat(dao.claimDelivery(42L, "current-attempt", 5_000L)).isEqualTo(1)

        assertThat(dao.markDelivered(42L, "stale-attempt", 5_100L)).isEqualTo(0)
        assertThat(dao.getById(42L)!!.deliveryState)
            .isEqualTo(ProgramReminderDeliveryState.DELIVERING)
    }
}
