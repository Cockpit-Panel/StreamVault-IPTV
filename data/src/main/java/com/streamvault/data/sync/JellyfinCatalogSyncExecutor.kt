package com.streamvault.data.sync

import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.ProviderWorkflowDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.remote.jellyfin.JellyfinCatalogLimitException
import com.streamvault.data.remote.jellyfin.JellyfinItemLimitException
import com.streamvault.data.remote.jellyfin.JellyfinPaginationException
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.jellyfin.JellyfinResponseTooLargeException
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.IOException

/** Owns Jellyfin pagination, checkpoint recovery, staging, and catalog activation. */
internal class JellyfinCatalogSyncExecutor(
    private val jellyfinProvider: JellyfinProvider,
    private val credentialCrypto: CredentialCrypto,
    private val syncCatalogStore: SyncCatalogStore,
    private val catalogSyncDao: CatalogSyncDao,
    private val providerWorkflowDao: ProviderWorkflowDao?,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit
) {
    suspend fun syncFull(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?,
        afterCatalogApply: suspend () -> Unit = {}
    ): SyncOutcome {
        val decryptedProvider = provider.copy(password = credentialCrypto.decryptIfNeeded(provider.password))
        val restoredCheckpoint = if (force) null else loadCheckpoint(provider.id)
        val canResume = restoredCheckpoint != null && isCheckpointConsistent(provider.id, restoredCheckpoint)
        var checkpoint = if (canResume) {
            requireNotNull(restoredCheckpoint)
        } else {
            withContext(NonCancellable) { syncCatalogStore.clearProviderStaging(provider.id) }
            JellyfinCatalogCheckpoint(
                movieSessionId = syncCatalogStore.newSessionId(),
                seriesSessionId = syncCatalogStore.newSessionId(),
                phase = JellyfinCatalogPhase.MOVIES
            ).also { saveCheckpoint(provider.id, it) }
        }

        try {
            if (checkpoint.phase == JellyfinCatalogPhase.MOVIES) {
                progress(provider.id, onProgress, "Loading Jellyfin movies...")
                do {
                    when (val result = jellyfinProvider.fetchMoviesPage(decryptedProvider, checkpoint.movieStartIndex)) {
                        is Result.Error -> throw result.exception ?: IllegalStateException(result.message)
                        is Result.Success -> {
                            val page = result.data
                            syncCatalogStore.stageMovieBatch(provider.id, checkpoint.movieSessionId, page.items)
                            checkpoint = checkpoint.afterMoviePage(
                                reportedTotal = page.totalRecordCount,
                                pageItemCount = page.items.size,
                                stagedCount = catalogSyncDao.countMovieStages(provider.id, checkpoint.movieSessionId)
                            )
                            saveCheckpoint(provider.id, checkpoint)
                        }
                        is Result.Loading -> throw JellyfinPaginationException("Jellyfin movie page did not complete")
                    }
                } while (checkpoint.movieStartIndex < (checkpoint.movieTotal ?: 0))
                checkpoint = checkpoint.copy(phase = JellyfinCatalogPhase.SERIES)
                saveCheckpoint(provider.id, checkpoint)
            }

            if (checkpoint.phase == JellyfinCatalogPhase.SERIES) {
                progress(provider.id, onProgress, "Loading Jellyfin series...")
                do {
                    when (val result = jellyfinProvider.fetchSeriesPage(decryptedProvider, checkpoint.seriesStartIndex)) {
                        is Result.Error -> throw result.exception ?: IllegalStateException(result.message)
                        is Result.Success -> {
                            val page = result.data
                            syncCatalogStore.stageSeriesBatch(provider.id, checkpoint.seriesSessionId, page.items)
                            checkpoint = checkpoint.afterSeriesPage(
                                reportedTotal = page.totalRecordCount,
                                pageItemCount = page.items.size,
                                stagedCount = catalogSyncDao.countSeriesStages(provider.id, checkpoint.seriesSessionId)
                            )
                            saveCheckpoint(provider.id, checkpoint)
                        }
                        is Result.Loading -> throw JellyfinPaginationException("Jellyfin series page did not complete")
                    }
                } while (checkpoint.seriesStartIndex < (checkpoint.seriesTotal ?: 0))
                checkpoint = checkpoint.copy(phase = JellyfinCatalogPhase.READY)
                saveCheckpoint(provider.id, checkpoint)
            }

            progress(provider.id, onProgress, "Importing Jellyfin library...")
            syncCatalogStore.applyStagedJellyfinCatalog(
                providerId = provider.id,
                movieSessionId = checkpoint.movieSessionId,
                seriesSessionId = checkpoint.seriesSessionId,
                movieCategories = listOf(
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = 1L,
                        name = "Movies",
                        type = ContentType.MOVIE
                    )
                ),
                seriesCategories = listOf(
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = 2L,
                        name = "Series",
                        type = ContentType.SERIES
                    )
                ),
                afterCatalogApply = afterCatalogApply
            )
            val activatedMutations = checkpoint.movieStartIndex + checkpoint.seriesStartIndex
            saveCheckpoint(provider.id, null)
            return SyncOutcome(
                stagedMutations = activatedMutations,
                activation = SyncActivation.ACTIVATED_CATALOG
            )
        } catch (cancelled: CancellationException) {
            if (!hasDurableWorkflowContext(provider.id)) {
                withContext(NonCancellable) { syncCatalogStore.clearProviderStaging(provider.id) }
            }
            throw cancelled
        } catch (error: Exception) {
            if (!isResumableFailure(error) || !hasDurableWorkflowContext(provider.id)) {
                withContext(NonCancellable) {
                    syncCatalogStore.clearProviderStaging(provider.id)
                    saveCheckpoint(provider.id, null)
                }
            }
            throw error
        }
    }

    suspend fun syncMovies(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        progress(provider.id, onProgress, "Retrying Jellyfin movies...")
        val sessionId = syncCatalogStore.newSessionId()
        var startIndex = 0
        var expectedTotal: Int? = null
        try {
            do {
                when (val result = jellyfinProvider.fetchMoviesPage(provider, startIndex)) {
                    is Result.Success -> {
                        val page = result.data
                        expectedTotal = expectedTotal ?: page.totalRecordCount
                        if (page.totalRecordCount != expectedTotal) {
                            throw JellyfinPaginationException("Jellyfin movie catalog changed during retry")
                        }
                        syncCatalogStore.stageMovieBatch(provider.id, sessionId, page.items)
                        startIndex = page.nextStartIndex
                    }
                    is Result.Error -> throw result.exception ?: IllegalStateException(result.message)
                    is Result.Loading -> throw JellyfinPaginationException("Jellyfin movie retry did not complete")
                }
            } while (startIndex < (expectedTotal ?: 0))
            syncCatalogStore.applyStagedMovieCatalog(
                providerId = provider.id,
                sessionId = sessionId,
                categories = listOf(
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = 1L,
                        name = "Movies",
                        type = ContentType.MOVIE
                    )
                )
            )
            val now = System.currentTimeMillis()
            val metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
            syncMetadataRepository.updateMetadata(
                metadata.copy(
                    lastMovieSync = now,
                    lastMovieAttempt = now,
                    lastMovieSuccess = now,
                    movieCount = movieDao.getCount(provider.id).first(),
                    movieCatalogStale = false
                )
            )
            return SyncOutcome(
                stagedMutations = startIndex,
                activation = SyncActivation.ACTIVATED_CATALOG
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { syncCatalogStore.discardStagedImport(provider.id, sessionId) }
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable) { syncCatalogStore.discardStagedImport(provider.id, sessionId) }
            throw error
        }
    }

    suspend fun syncSeries(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        progress(provider.id, onProgress, "Retrying Jellyfin series...")
        val sessionId = syncCatalogStore.newSessionId()
        var startIndex = 0
        var expectedTotal: Int? = null
        try {
            do {
                when (val result = jellyfinProvider.fetchSeriesPage(provider, startIndex)) {
                    is Result.Success -> {
                        val page = result.data
                        expectedTotal = expectedTotal ?: page.totalRecordCount
                        if (page.totalRecordCount != expectedTotal) {
                            throw JellyfinPaginationException("Jellyfin series catalog changed during retry")
                        }
                        syncCatalogStore.stageSeriesBatch(provider.id, sessionId, page.items)
                        startIndex = page.nextStartIndex
                    }
                    is Result.Error -> throw result.exception ?: IllegalStateException(result.message)
                    is Result.Loading -> throw JellyfinPaginationException("Jellyfin series retry did not complete")
                }
            } while (startIndex < (expectedTotal ?: 0))
            syncCatalogStore.applyStagedSeriesCatalog(
                providerId = provider.id,
                sessionId = sessionId,
                categories = listOf(
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = 2L,
                        name = "Series",
                        type = ContentType.SERIES
                    )
                )
            )
            val now = System.currentTimeMillis()
            val metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
            syncMetadataRepository.updateMetadata(
                metadata.copy(
                    lastSeriesSync = now,
                    seriesCount = seriesDao.getCount(provider.id).first()
                )
            )
            return SyncOutcome(
                stagedMutations = startIndex,
                activation = SyncActivation.ACTIVATED_CATALOG
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { syncCatalogStore.discardStagedImport(provider.id, sessionId) }
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable) { syncCatalogStore.discardStagedImport(provider.id, sessionId) }
            throw error
        }
    }

    private suspend fun loadCheckpoint(providerId: Long): JellyfinCatalogCheckpoint? {
        val lease = coroutineContext[ProviderWorkflowExecutionContext]?.lease ?: return null
        if (lease.providerId != providerId) return null
        return JellyfinCatalogCheckpoint.decode(
            providerWorkflowDao?.getCheckpoint(providerId, lease.generation, lease.phase)
        )
    }

    private suspend fun saveCheckpoint(providerId: Long, checkpoint: JellyfinCatalogCheckpoint?) {
        val lease = coroutineContext[ProviderWorkflowExecutionContext]?.lease ?: return
        if (lease.providerId != providerId) {
            throw ProviderWorkflowSupersededException(providerId, lease.generation)
        }
        val workflowDao = providerWorkflowDao ?: return
        if (workflowDao.updateRunningCheckpoint(
                providerId = providerId,
                generation = lease.generation,
                phase = lease.phase,
                token = lease.token,
                checkpoint = checkpoint?.encode(),
                now = System.currentTimeMillis()
            ) != 1
        ) {
            throw ProviderWorkflowSupersededException(providerId, lease.generation)
        }
    }

    private suspend fun isCheckpointConsistent(
        providerId: Long,
        checkpoint: JellyfinCatalogCheckpoint
    ): Boolean = checkpoint.isConsistent(
        catalogSyncDao.countMovieStages(providerId, checkpoint.movieSessionId),
        catalogSyncDao.countSeriesStages(providerId, checkpoint.seriesSessionId)
    )

    private suspend fun hasDurableWorkflowContext(providerId: Long): Boolean =
        coroutineContext[ProviderWorkflowExecutionContext]?.lease?.providerId == providerId &&
            providerWorkflowDao != null

    private fun isResumableFailure(error: Exception): Boolean =
        error is IOException &&
            error !is JellyfinPaginationException &&
            error !is JellyfinCatalogLimitException &&
            error !is JellyfinResponseTooLargeException &&
            error !is JellyfinItemLimitException
}
