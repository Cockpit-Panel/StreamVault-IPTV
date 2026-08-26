package com.streamvault.data.repository

import com.streamvault.data.local.dao.*
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.mapper.toDomain
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val transactionRunner: DatabaseTransactionRunner
) : CategoryRepository {

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.LIVE.name),
            categoryDao.getByProviderAndType(providerId, ContentType.MOVIE.name),
            categoryDao.getByProviderAndType(providerId, ContentType.SERIES.name),
            categoryDao.getByProviderAndType(providerId, ContentType.VOD.name)
        ) { live, movies, series, vod ->
            (live + movies + series + vod)
                .map { it.toDomain() }
                .sortedWith(compareBy<Category>({ it.type.ordinal }, { it.name.lowercase() }))
        }

    override suspend fun setCategoryProtection(
        providerId: Long,
        categoryId: Long,
        type: ContentType,
        isProtected: Boolean
    ): Result<Unit> = try {
        transactionRunner.inTransaction {
            categoryDao.updateProtectionStatus(providerId, categoryId, type.name, isProtected)
            when (type) {
                ContentType.LIVE -> channelDao.updateProtectionStatus(providerId, categoryId, isProtected)
                ContentType.MOVIE -> movieDao.updateProtectionStatus(providerId, categoryId, isProtected)
                ContentType.SERIES -> seriesDao.updateProtectionStatus(providerId, categoryId, isProtected)
                ContentType.VOD -> {
                    movieDao.updateProtectionStatus(providerId, categoryId, isProtected)
                    seriesDao.updateProtectionStatus(providerId, categoryId, isProtected)
                }
                ContentType.SERIES_EPISODE -> Unit
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update category protection", e)
    }
}
