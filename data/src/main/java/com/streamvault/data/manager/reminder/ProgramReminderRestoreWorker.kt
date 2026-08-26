package com.streamvault.data.manager.reminder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamvault.data.manager.ProgramReminderManagerImpl
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Durable startup and exact-alarm-permission restoration for program reminders. */
class ProgramReminderRestoreWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProgramReminderRestoreWorkerEntryPoint {
        fun reminderManager(): ProgramReminderManagerImpl
    }

    override suspend fun doWork(): Result = try {
        EntryPointAccessors.fromApplication(
            applicationContext,
            ProgramReminderRestoreWorkerEntryPoint::class.java
        ).reminderManager().restoreScheduledReminders()
        Result.success()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.retry()
    }

    companion object {
        internal const val ONE_SHOT_WORK_NAME = "ProgramReminderRestoreWorkerOneShot"
        private const val BACKOFF_MILLIS = 30_000L

        fun enqueueOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProgramReminderRestoreWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
