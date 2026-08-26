package com.streamvault.data.repository

import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.VodCatalogEntryDao
import com.streamvault.data.local.dao.VodCategoryHydrationDao
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.CatalogHydrationCommands
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodCategoryHydration
import com.streamvault.domain.model.VodCategoryHydrationRequest
import com.streamvault.domain.model.VodCategoryLoadMode
import com.streamvault.domain.repository.VodRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class VodRepositoryImpl @Inject constructor(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val vodCategoryHydrationDao: VodCategoryHydrationDao,
    private val vodCatalogEntryDao: VodCatalogEntryDao,
    private val categoryDao: CategoryDao,
    private val preferencesRepository: PreferencesRepository,
    private val syncManager: CatalogHydrationCommands
) : VodRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getCategories(providerId: Long): Flow<List<Category>> = combine(
        categoryDao.getByProviderAndType(providerId, ContentType.VOD.name),
        preferencesRepository.parentalControlLevel,
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.VOD)
    ) { entities, parentalLevel, hiddenIds ->
        entities.asSequence()
            .filterNot { it.categoryId in hiddenIds }
            .filter { parentalLevel < 3 || (!it.isAdult && !it.isUserProtected) }
            .map { it.toDomain() }
            .toList()
    }

    override fun getCategoryPreview(
        providerId: Long,
        categoryId: Long,
        limit: Int
    ): Flow<List<VodCatalogItem>> = flow {
        ensurePreview(providerId, categoryId)
        emitAll(observeOrderedItems(providerId, categoryId, limit))
    }

    override fun getCategoryItems(
        providerId: Long,
        categoryId: Long
    ): Flow<List<VodCatalogItem>> = observeOrderedItems(providerId, categoryId, Int.MAX_VALUE)

    override fun observeHydration(
        providerId: Long,
        categoryId: Long
    ): Flow<VodCategoryHydration?> = vodCategoryHydrationDao.observe(providerId, categoryId).map { entity ->
        entity?.let {
            VodCategoryHydration(
                lastSuccessfulPage = it.lastSuccessfulPage,
                totalPages = it.totalPages,
                advertisedTotalItems = it.advertisedTotalItems,
                advertisedTotalPages = it.advertisedTotalPages,
                pageSize = it.pageSize,
                itemCount = it.itemCount,
                isComplete = it.isComplete,
                isTruncated = it.lastStatus == "TRUNCATED",
                hasMovies = it.hasMovies,
                hasSeries = it.hasSeries,
                isLoading = it.lastStatus == "RUNNING",
                error = it.lastError
            )
        }
    }

    override suspend fun ensurePreview(
        providerId: Long,
        categoryId: Long
    ): Result<Unit> = syncManager.hydrateUnifiedVodCategory(
        providerId = providerId,
        categoryId = categoryId,
        request = VodCategoryHydrationRequest.OPEN
    )

    override suspend fun requestCategoryHydration(
        providerId: Long,
        categoryId: Long,
        request: VodCategoryHydrationRequest
    ): Result<Unit> {
        val loadMode = preferencesRepository.vodCategoryLoadMode.first()
        val effectiveRequest = if (
            request == VodCategoryHydrationRequest.OPEN && loadMode == VodCategoryLoadMode.COMPLETE_ON_OPEN
        ) VodCategoryHydrationRequest.COMPLETE else request
        val result = syncManager.hydrateUnifiedVodCategory(providerId, categoryId, effectiveRequest)
        if (result is Result.Success && request == VodCategoryHydrationRequest.OPEN &&
            loadMode == VodCategoryLoadMode.PAGED
        ) {
            repositoryScope.launch {
                syncManager.hydrateUnifiedVodCategory(
                    providerId,
                    categoryId,
                    VodCategoryHydrationRequest.NEXT_PAGE
                )
            }
        }
        return result
    }

    override suspend fun hydrateCompletely(providerId: Long, categoryId: Long): Result<Unit> =
        syncManager.hydrateUnifiedVodCategory(
            providerId = providerId,
            categoryId = categoryId,
            request = VodCategoryHydrationRequest.COMPLETE
        )

    private fun observeOrderedItems(
        providerId: Long,
        categoryId: Long,
        limit: Int
    ): Flow<List<VodCatalogItem>> = vodCatalogEntryDao
        .observeByCategory(providerId, categoryId)
        .flatMapLatest { entries ->
            if (entries.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val movieIds = entries.filter { it.itemType == ContentType.MOVIE }.map { it.targetId }.distinct()
            val seriesIds = entries.filter { it.itemType == ContentType.SERIES }.map { it.targetId }.distinct()
            combine(
                if (movieIds.isEmpty()) flowOf(emptyList()) else movieDao.observeByStreamIds(providerId, movieIds),
                if (seriesIds.isEmpty()) flowOf(emptyList()) else seriesDao.observeBySeriesIds(providerId, seriesIds)
            ) { movies, series ->
                val moviesById = movies.associateBy { it.streamId }
                val seriesById = series.associateBy { it.seriesId }
                entries.asSequence().mapNotNull { entry ->
                    when (entry.itemType) {
                        ContentType.MOVIE -> moviesById[entry.targetId]?.toDomain()?.let(VodCatalogItem::MovieItem)
                        ContentType.SERIES -> seriesById[entry.targetId]?.toDomain()?.let(VodCatalogItem::SeriesItem)
                        else -> null
                    }
                }.take(limit).toList()
            }
        }
}
