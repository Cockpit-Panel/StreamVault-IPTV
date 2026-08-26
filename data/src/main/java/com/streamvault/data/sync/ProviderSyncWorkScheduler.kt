package com.streamvault.data.sync

import android.content.Context
import com.streamvault.domain.model.ContentType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns durable provider-sync work scheduling.
 *
 * Catalog plans may decide what work is needed, but they do not construct
 * WorkManager requests themselves. Keeping this policy here also makes sync
 * orchestration testable without coupling every provider plan to Android.
 */
@Singleton
class ProviderSyncWorkScheduler @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context
) {
    fun cancelBackgroundEpg(providerId: Long) {
        BackgroundEpgSyncWorker.cancel(applicationContext, providerId)
    }

    fun cancelStalkerIndex(providerId: Long) {
        StalkerIndexWorker.cancel(applicationContext, providerId)
    }

    fun scheduleBackgroundEpg(providerId: Long) {
        BackgroundEpgSyncWorker.enqueue(applicationContext, providerId)
    }

    fun scheduleProviderResume(providerId: Long, configurationGeneration: Long? = null) {
        if (configurationGeneration != null) {
            ProviderSyncWorker.enqueueProviderConfigRevision(
                context = applicationContext,
                providerId = providerId,
                revision = configurationGeneration
            )
        } else {
            ProviderSyncWorker.enqueueProvider(applicationContext, providerId)
        }
    }

    fun scheduleXtreamIndex(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false
    ) {
        XtreamIndexWorker.enqueue(
            context = applicationContext,
            providerId = providerId,
            section = section?.name,
            force = force
        )
    }

    fun scheduleStalkerIndex(
        providerId: Long,
        force: Boolean = false,
        initialDelaySeconds: Long = 0L,
        appendSuccessor: Boolean = false
    ) {
        // Stalker section requests are intentionally coalesced into the durable
        // provider job. This preserves the existing worker contract: the worker
        // selects the next pending section from its persisted job state.
        StalkerIndexWorker.enqueue(
            context = applicationContext,
            providerId = providerId,
            section = null,
            force = force,
            initialDelaySeconds = initialDelaySeconds,
            appendSuccessor = appendSuccessor
        )
    }

}
