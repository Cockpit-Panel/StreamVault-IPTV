package com.streamvault.data.manager.recording

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.streamvault.domain.model.RecordingReconciliationResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class RecordingReconcileWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RecordingWorkerEntryPoint {
        fun recordingManager(): com.streamvault.domain.manager.RecordingManager
    }

    override suspend fun doWork(): Result {
        val manager = EntryPointAccessors.fromApplication(
            applicationContext,
            RecordingWorkerEntryPoint::class.java
        ).recordingManager()
        val reconciliation = manager.reconcileRecordingState()
        setProgress(reconciliationDiagnosticData(reconciliation, runAttemptCount))
        return reconciliationWorkResult(
            result = reconciliation,
            runAttemptCount = runAttemptCount,
            isOneShot = inputData.getBoolean(KEY_ONE_SHOT, false)
        )
    }

    companion object {
        private const val PERIODIC_WORK_NAME = RECORDING_RECONCILE_PERIODIC_WORK_NAME
        private const val ONE_SHOT_WORK_NAME = RECORDING_RECONCILE_ONE_SHOT_WORK_NAME
        private const val KEY_ONE_SHOT = "one_shot"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecordingReconcileWorker>(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun enqueueOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecordingReconcileWorker>()
                .setInputData(workDataOf(KEY_ONE_SHOT to true))
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RECORDING_RECONCILE_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                recordingReconcileOneShotExistingWorkPolicy(),
                request
            )
        }
    }
}

internal const val RECORDING_RECONCILE_PERIODIC_WORK_NAME = "RecordingReconcileWorker"
internal const val RECORDING_RECONCILE_ONE_SHOT_WORK_NAME = "RecordingReconcileWorkerOneShot"
internal const val RECORDING_RECONCILE_MAX_ONE_SHOT_ATTEMPTS = 3
internal const val RECORDING_RECONCILE_BACKOFF_MILLIS = 30_000L
internal const val RECONCILIATION_DIAGNOSTIC_OUTCOME = "outcome"
internal const val RECONCILIATION_DIAGNOSTIC_ATTEMPT = "attempt"
internal const val RECONCILIATION_DIAGNOSTIC_INSPECTED = "rows_inspected"
internal const val RECONCILIATION_DIAGNOSTIC_REPAIRED = "rows_repaired"
internal const val RECONCILIATION_DIAGNOSTIC_QUARANTINED = "rows_quarantined"
internal const val RECONCILIATION_DIAGNOSTIC_MESSAGE = "message"

internal fun recordingReconcileOneShotExistingWorkPolicy(): ExistingWorkPolicy =
    ExistingWorkPolicy.KEEP

internal fun reconciliationWorkResult(
    result: RecordingReconciliationResult,
    runAttemptCount: Int,
    isOneShot: Boolean,
): androidx.work.ListenableWorker.Result = when (result) {
    is RecordingReconciliationResult.Complete,
    is RecordingReconciliationResult.Partial ->
        androidx.work.ListenableWorker.Result.success(
            reconciliationDiagnosticData(result, runAttemptCount)
        )
    is RecordingReconciliationResult.PermanentFailure ->
        androidx.work.ListenableWorker.Result.failure(
            reconciliationDiagnosticData(result, runAttemptCount)
        )
    is RecordingReconciliationResult.TransientFailure -> {
        val attemptCeilingReached = isOneShot &&
            runAttemptCount >= RECORDING_RECONCILE_MAX_ONE_SHOT_ATTEMPTS - 1
        if (attemptCeilingReached) {
            androidx.work.ListenableWorker.Result.failure(
                reconciliationDiagnosticData(result, runAttemptCount)
            )
        } else {
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}

internal fun reconciliationDiagnosticData(
    result: RecordingReconciliationResult,
    runAttemptCount: Int
): androidx.work.Data {
    val outcome = when (result) {
        is RecordingReconciliationResult.Complete -> "complete"
        is RecordingReconciliationResult.Partial -> "partial"
        is RecordingReconciliationResult.TransientFailure -> "transient_failure"
        is RecordingReconciliationResult.PermanentFailure -> "permanent_failure"
    }
    val summary = when (result) {
        is RecordingReconciliationResult.Complete -> result.summary
        is RecordingReconciliationResult.Partial -> result.summary
        else -> null
    }
    val message = when (result) {
        is RecordingReconciliationResult.TransientFailure -> result.message
        is RecordingReconciliationResult.PermanentFailure -> result.message
        is RecordingReconciliationResult.Partial ->
            result.rowFailures.firstOrNull()?.reason.orEmpty()
        is RecordingReconciliationResult.Complete -> ""
    }
    return workDataOf(
        RECONCILIATION_DIAGNOSTIC_OUTCOME to outcome,
        RECONCILIATION_DIAGNOSTIC_ATTEMPT to runAttemptCount,
        RECONCILIATION_DIAGNOSTIC_INSPECTED to (summary?.rowsInspected ?: 0),
        RECONCILIATION_DIAGNOSTIC_REPAIRED to (summary?.rowsRepaired ?: 0),
        RECONCILIATION_DIAGNOSTIC_QUARANTINED to (summary?.rowsQuarantined ?: 0),
        RECONCILIATION_DIAGNOSTIC_MESSAGE to message.take(1_024)
    )
}
