package com.streamvault.app.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result as DomainResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class AppUpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun preferencesRepository(): PreferencesRepository
        fun gitHubReleaseChecker(): GitHubReleaseChecker
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java)
        val preferences = entry.preferencesRepository()
        if (!preferences.autoCheckAppUpdates.first()) return Result.success()

        val now = System.currentTimeMillis()
        if (runAttemptCount == 0 && !AppUpdateCheckPolicy.shouldAutoCheck(
                now,
                preferences.lastAppUpdateCheckTimestamp.first(),
                preferences.lastAppUpdateFailureTimestamp.first()
            )
        ) {
            return Result.success()
        }

        preferences.setLastAppUpdateAttemptTimestamp(now)
        preferences.setLastAppUpdateOutcome("ATTEMPTED")
        return when (val result = entry.gitHubReleaseChecker().fetchLatestRelease()) {
            is DomainResult.Success -> {
                preferences.setCachedAppUpdateRelease(
                    versionName = result.data.versionName,
                    versionCode = result.data.versionCode,
                    releaseUrl = result.data.releaseUrl,
                    downloadUrl = result.data.downloadUrl,
                    downloadSha256 = result.data.downloadSha256,
                    releaseNotes = result.data.releaseNotes,
                    publishedAt = result.data.publishedAt
                )
                preferences.setLastAppUpdateCheckTimestamp(now)
                preferences.setLastAppUpdateFailureTimestamp(null)
                preferences.setLastAppUpdateOutcome("SUCCESS")
                Result.success()
            }
            is DomainResult.Error -> {
                preferences.setLastAppUpdateFailureTimestamp(now)
                preferences.setLastAppUpdateOutcome("FAILURE: ${result.message}")
                Result.retry()
            }
            DomainResult.Loading -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "app-update-check"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AppUpdateCheckWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
