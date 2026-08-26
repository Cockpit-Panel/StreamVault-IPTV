package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderWorkflowIntegrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: StreamVaultDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun processRestart_reopensDurableLeaseAndReclaimsExpiredPhase() = runTest {
        var db = openDatabase()
        db.providerDao().insert(provider(PROVIDER_ID))
        val originalTicket = db.providerWorkflowDao().request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.EPG,
            reason = ProviderWorkflowReason.PERIODIC,
            now = 100L
        )
        checkNotNull(
            db.providerWorkflowDao().claim(
                originalTicket,
                token = "dead-process",
                now = 101L,
                leaseDurationMs = 100L,
                staleHeartbeatBefore = 0L
            )
        )
        db.close()
        database = null

        db = openDatabase()
        val restartedTicket = db.providerWorkflowDao().request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.EPG,
            reason = ProviderWorkflowReason.RECOVERY,
            now = 202L,
            staleHeartbeatBefore = 150L
        )
        val recovered = db.providerWorkflowDao().claim(
            restartedTicket,
            token = "new-process",
            now = 202L,
            leaseDurationMs = 1_000L,
            staleHeartbeatBefore = 150L
        )

        assertThat(recovered).isNotNull()
        assertThat(recovered!!.generation).isEqualTo(originalTicket.generation)
        assertThat(
            db.providerWorkflowDao()
                .getPhases(PROVIDER_ID, originalTicket.generation)
                .single()
                .attemptCount
        ).isEqualTo(2)
    }

    @Test
    fun forcedManualRefresh_supersedesEveryBackgroundWorkerPhase() = runTest {
        val db = openDatabase()
        val backgroundPhases = listOf(
            ProviderWorkflowPhase.PRIMARY_CATALOG,
            ProviderWorkflowPhase.CONTENT_INDEX,
            ProviderWorkflowPhase.MOVIE_INDEX,
            ProviderWorkflowPhase.SERIES_INDEX,
            ProviderWorkflowPhase.EPG,
            ProviderWorkflowPhase.FINALIZE
        )

        backgroundPhases.forEachIndexed { index, phase ->
            val providerId = index + 1L
            db.providerDao().insert(provider(providerId))
            val background = db.providerWorkflowDao().request(
                providerId = providerId,
                phase = phase,
                reason = ProviderWorkflowReason.PERIODIC,
                now = 100L + index,
                priority = 0
            )
            val oldLease = checkNotNull(
                db.providerWorkflowDao().claim(
                    background,
                    token = "background-$phase",
                    now = 200L + index,
                    leaseDurationMs = 10_000L,
                    staleHeartbeatBefore = 0L
                )
            )

            val manual = db.providerWorkflowDao().request(
                providerId = providerId,
                phase = ProviderWorkflowPhase.PRIMARY_CATALOG,
                reason = ProviderWorkflowReason.MANUAL,
                now = 300L + index,
                supersede = true,
                priority = 50,
                force = true
            )

            assertThat(manual.admitted).isTrue()
            assertThat(manual.generation).isEqualTo(background.generation + 1L)
            assertThat(db.providerWorkflowDao().complete(oldLease, now = 400L + index)).isFalse()
            assertThat(
                db.providerWorkflowDao().claim(
                    manual,
                    token = "manual-$phase",
                    now = 400L + index,
                    leaseDurationMs = 10_000L,
                    staleHeartbeatBefore = 0L
                )
            ).isNotNull()
        }
    }

    @Test
    fun forcedManualRefresh_cannotSupersedeConfigurationMigration() = runTest {
        val db = openDatabase()
        db.providerDao().insert(provider(PROVIDER_ID))
        val config = db.providerWorkflowDao().request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.PREPARE,
            reason = ProviderWorkflowReason.CONFIG_CHANGE,
            now = 100L,
            supersede = true,
            priority = 100,
            force = true
        )

        val manual = db.providerWorkflowDao().request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.PRIMARY_CATALOG,
            reason = ProviderWorkflowReason.MANUAL,
            now = 101L,
            supersede = true,
            priority = 50,
            force = true
        )

        assertThat(manual.admitted).isFalse()
        assertThat(manual.generation).isEqualTo(config.generation)
        assertThat(db.providerWorkflowDao().getWorkflow(PROVIDER_ID)!!.reason)
            .isEqualTo(ProviderWorkflowReason.CONFIG_CHANGE)
    }

    private fun openDatabase(): StreamVaultDatabase {
        return Room.databaseBuilder(
            context,
            StreamVaultDatabase::class.java,
            DATABASE_NAME
        ).allowMainThreadQueries().build().also { database = it }
    }

    private fun provider(id: Long) = ProviderEntity(
        id = id,
        name = "Provider $id",
        type = ProviderType.M3U
    )

    private companion object {
        const val DATABASE_NAME = "provider-workflow-integration.db"
        const val PROVIDER_ID = 1L
    }
}
