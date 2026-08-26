package com.streamvault.data.manager.recording

import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.RecordingReconciliationResult
import com.streamvault.domain.model.RecordingReconciliationRowFailure
import com.streamvault.domain.model.RecordingReconciliationSummary
import org.junit.Test

class RecordingReconcileWorkerTest {

    @Test
    fun `transient failure retries and a later success completes`() {
        val transient = RecordingReconciliationResult.TransientFailure("database busy")
        val complete = RecordingReconciliationResult.Complete(
            RecordingReconciliationSummary(rowsInspected = 2, rowsRepaired = 1)
        )

        assertThat(reconciliationWorkResult(transient, 0, isOneShot = true))
            .isEqualTo(ListenableWorker.Result.retry())
        assertThat(reconciliationWorkResult(complete, 1, isOneShot = true))
            .isEqualTo(
                ListenableWorker.Result.success(reconciliationDiagnosticData(complete, 1))
            )
    }

    @Test
    fun `permanent failure stops immediately and partial row failure is committed`() {
        val permanent = RecordingReconciliationResult.PermanentFailure("malformed configuration")
        val partial = RecordingReconciliationResult.Partial(
            summary = RecordingReconciliationSummary(
                rowsInspected = 3,
                rowsRepaired = 2,
                rowsQuarantined = 1
            ),
            rowFailures = listOf(
                RecordingReconciliationRowFailure("bad-row", "invalid schedule")
            )
        )

        assertThat(reconciliationWorkResult(permanent, 0, isOneShot = true))
            .isEqualTo(
                ListenableWorker.Result.failure(reconciliationDiagnosticData(permanent, 0))
            )
        assertThat(reconciliationWorkResult(partial, 0, isOneShot = true))
            .isEqualTo(
                ListenableWorker.Result.success(reconciliationDiagnosticData(partial, 0))
            )
    }

    @Test
    fun `one shot transient retry has exponential backoff and a three attempt ceiling`() {
        val transient = RecordingReconciliationResult.TransientFailure("locked")

        assertThat(RECORDING_RECONCILE_BACKOFF_MILLIS).isEqualTo(30_000L)
        assertThat(RECORDING_RECONCILE_MAX_ONE_SHOT_ATTEMPTS).isEqualTo(3)
        assertThat(reconciliationWorkResult(transient, 0, isOneShot = true))
            .isEqualTo(ListenableWorker.Result.retry())
        assertThat(reconciliationWorkResult(transient, 1, isOneShot = true))
            .isEqualTo(ListenableWorker.Result.retry())
        assertThat(reconciliationWorkResult(transient, 2, isOneShot = true))
            .isEqualTo(
                ListenableWorker.Result.failure(reconciliationDiagnosticData(transient, 2))
            )
    }

    @Test
    fun `periodic transient failure is not consumed by one shot ceiling`() {
        val transient = RecordingReconciliationResult.TransientFailure("temporarily unavailable")

        assertThat(reconciliationWorkResult(transient, 9, isOneShot = false))
            .isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `startup enqueue keeps unfinished one shot instead of resetting attempt history`() {
        assertThat(recordingReconcileOneShotExistingWorkPolicy())
            .isEqualTo(ExistingWorkPolicy.KEEP)
    }

    @Test
    fun `diagnostic metadata reports outcome counts attempt and bounded reason`() {
        val partial = RecordingReconciliationResult.Partial(
            summary = RecordingReconciliationSummary(4, 3, 1),
            rowFailures = listOf(
                RecordingReconciliationRowFailure("row-7", "bad row")
            )
        )

        val diagnostics = reconciliationDiagnosticData(partial, 2)

        assertThat(diagnostics.getString(RECONCILIATION_DIAGNOSTIC_OUTCOME)).isEqualTo("partial")
        assertThat(diagnostics.getInt(RECONCILIATION_DIAGNOSTIC_ATTEMPT, -1)).isEqualTo(2)
        assertThat(diagnostics.getInt(RECONCILIATION_DIAGNOSTIC_INSPECTED, -1)).isEqualTo(4)
        assertThat(diagnostics.getInt(RECONCILIATION_DIAGNOSTIC_REPAIRED, -1)).isEqualTo(3)
        assertThat(diagnostics.getInt(RECONCILIATION_DIAGNOSTIC_QUARANTINED, -1)).isEqualTo(1)
        assertThat(diagnostics.getString(RECONCILIATION_DIAGNOSTIC_MESSAGE)).isEqualTo("bad row")
    }
}
