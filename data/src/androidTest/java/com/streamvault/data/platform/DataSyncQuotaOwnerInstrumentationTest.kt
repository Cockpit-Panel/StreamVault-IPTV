package com.streamvault.data.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that the app-wide quota ledger survives a new owner/process boundary. */
@RunWith(AndroidJUnit4::class)
class DataSyncQuotaOwnerInstrumentationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearLedger()
    }

    @After
    fun tearDown() {
        clearLedger()
    }

    @Test
    fun persistedSessionsAreChargedAndRecoveredByTheNextProcess() {
        val firstProcess = DataSyncQuotaOwner(context)
        val recording = firstProcess.acquire(DataSyncServiceOwner.RECORDING, nowMs = 1_000L)
            .requireGranted()
        firstProcess.acquire(DataSyncServiceOwner.DOWNLOAD, nowMs = 1_000L)
            .requireGranted()

        assertThat(firstProcess.snapshot(nowMs = 4_000L).consumedMs).isEqualTo(6_000L)

        val restartedProcess = DataSyncQuotaOwner(context)
        val recovered = restartedProcess.snapshot(nowMs = 5_000L)

        assertThat(recovered.consumedMs).isEqualTo(8_000L)
        assertThat(recovered.activeOwners).isEmpty()

        // The old lease is intentionally no longer active after restart; releasing it must be
        // harmless and must not debit the next process's ledger a second time.
        val afterLateRelease = restartedProcess.release(recording, nowMs = 6_000L)
        assertThat(afterLateRelease.consumedMs).isEqualTo(8_000L)
    }

    private fun clearLedger() {
        context.getSharedPreferences("foreground_service_quota", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun DataSyncQuotaAcquireResult.requireGranted(): DataSyncQuotaLease =
        (this as DataSyncQuotaAcquireResult.Granted).lease
}
