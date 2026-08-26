package com.streamvault.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DataSyncQuotaOwnerTest {

    @Test
    fun `recording and download sessions consume one shared budget`() {
        val ledger = DataSyncQuotaLedger(
            windowStartedAtMs = 1_000L,
            consumedMs = 0L,
            sessions = mutableMapOf(),
            maxQuotaMs = 10_000L,
            windowMs = 100_000L
        )

        val recording = ledger.acquire(DataSyncServiceOwner.RECORDING, 1_000L)
            as DataSyncQuotaAcquireResult.Granted
        val download = ledger.acquire(DataSyncServiceOwner.DOWNLOAD, 1_000L)
            as DataSyncQuotaAcquireResult.Granted

        ledger.snapshot(4_000L)

        assertThat(ledger.snapshot(4_000L).consumedMs).isEqualTo(6_000L)
        assertThat(ledger.snapshot(4_000L).activeOwners)
            .containsExactly(DataSyncServiceOwner.RECORDING, DataSyncServiceOwner.DOWNLOAD)

        ledger.release(recording.lease, 5_000L)
        ledger.release(download.lease, 6_000L)
        assertThat(ledger.snapshot(6_000L).consumedMs).isEqualTo(9_000L)
    }

    @Test
    fun `abandoned process session is charged on the next process operation`() {
        val firstProcess = DataSyncQuotaLedger(
            windowStartedAtMs = 1_000L,
            consumedMs = 0L,
            sessions = mutableMapOf(),
            maxQuotaMs = 10_000L,
            windowMs = 100_000L
        )
        firstProcess.acquire(DataSyncServiceOwner.RECORDING, 1_000L, processId = "process-a")

        val persisted = firstProcess.let {
            DataSyncQuotaLedger(it.windowStartedAtMs, it.consumedMs, it.sessions.toMutableMap(), 10_000L, 100_000L)
        }
        val restartedProcess = DataSyncQuotaLedger(
            persisted.windowStartedAtMs,
            persisted.consumedMs,
            persisted.sessions.toMutableMap(),
            10_000L,
            100_000L
        )

        assertThat(restartedProcess.snapshot(7_000L).consumedMs).isEqualTo(6_000L)
        restartedProcess.recoverAbandonedSessions("process-b", 7_000L)
        assertThat(restartedProcess.snapshot(7_000L).activeOwners).isEmpty()
    }

    @Test
    fun `quota exhaustion is shared and window rollover resets active sessions`() {
        val ledger = DataSyncQuotaLedger(
            windowStartedAtMs = 1_000L,
            consumedMs = 10_000L,
            sessions = mutableMapOf(),
            maxQuotaMs = 10_000L,
            windowMs = 10_000L
        )

        assertThat(ledger.acquire(DataSyncServiceOwner.DOWNLOAD, 2_000L))
            .isInstanceOf(DataSyncQuotaAcquireResult.Exhausted::class.java)

        val nextWindow = ledger.acquire(DataSyncServiceOwner.RECORDING, 11_000L)
        assertThat(nextWindow).isInstanceOf(DataSyncQuotaAcquireResult.Granted::class.java)
        assertThat(ledger.snapshot(11_000L).consumedMs).isEqualTo(0L)
    }
}
