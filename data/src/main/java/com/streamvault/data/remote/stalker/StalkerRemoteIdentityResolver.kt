package com.streamvault.data.remote.stalker

import android.database.sqlite.SQLiteConstraintException
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.StalkerRemoteIdentityDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.entity.StalkerRemoteIdentityEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.util.KeyedMutexRegistry
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Collision-safe, persistent translation between portal IDs and local Long IDs. */
@Singleton
class StalkerRemoteIdentityResolver private constructor(
    private val dao: StalkerRemoteIdentityDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val categoryDao: CategoryDao?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit
) {
    @Inject
    constructor(
        dao: StalkerRemoteIdentityDao,
        transactionRunner: DatabaseTransactionRunner,
        categoryDao: CategoryDao
    ) : this(dao, transactionRunner, categoryDao, Unit)

    internal constructor(
        dao: StalkerRemoteIdentityDao,
        transactionRunner: DatabaseTransactionRunner
    ) : this(dao, transactionRunner, null, Unit)

    private val locks = KeyedMutexRegistry<String>()

    suspend fun resolveAll(
        providerId: Long,
        contentType: ContentType,
        rawIds: Iterable<String>
    ): Map<String, Long> {
        val normalizedIds = rawIds.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        if (normalizedIds.isEmpty()) return emptyMap()
        val key = "$providerId/${contentType.name}"
        return locks.withLock(key) {
            transactionRunner.inTransaction {
                normalizedIds.associateWith { rawId -> resolveLocked(providerId, contentType, rawId) }
            }
        }
    }

    suspend fun reverse(providerId: Long, contentType: ContentType, surrogateId: Long): String? =
        dao.getBySurrogateId(providerId, contentType.name, surrogateId)?.rawId

    suspend fun resolveCategories(
        providerId: Long,
        contentType: ContentType,
        categories: Iterable<Pair<String, String>>
    ): Map<String, Long> {
        val normalized = categories
            .map { (rawId, name) -> rawId.trim() to name.trim() }
            .filter { (rawId, _) -> rawId.isNotEmpty() }
            .distinctBy { it.first }
            .sortedBy { it.first }
        if (normalized.isEmpty()) return emptyMap()
        val existingByName = categoryDao
            ?.getByProviderAndTypeSync(providerId, contentType.name)
            ?.associateBy { category -> category.name.trim().lowercase(Locale.ROOT) }
            .orEmpty()
        val preferred = normalized.associate { (rawId, name) ->
            rawId to existingByName[name.lowercase(Locale.ROOT)]?.categoryId
        }
        val key = "$providerId/${contentType.name}"
        return locks.withLock(key) {
            transactionRunner.inTransaction {
                normalized.associate { (rawId, _) ->
                    rawId to resolveLocked(providerId, contentType, rawId, preferred[rawId])
                }
            }
        }
    }

    private suspend fun resolveLocked(
        providerId: Long,
        contentType: ContentType,
        rawId: String,
        preferredSurrogate: Long? = null
    ): Long {
        dao.getByRawId(providerId, contentType.name, rawId)?.let { return it.surrogateId }
        val numericCandidate = rawId.toLongOrNull()?.takeIf { it > 0L }
        val legacyCandidate = legacyId(providerId, contentType, rawId)
        var candidate = listOfNotNull(preferredSurrogate?.takeIf { it > 0L }, numericCandidate, legacyCandidate)
            .firstOrNull { proposed ->
                dao.getBySurrogateId(providerId, contentType.name, proposed) == null
            }
            ?: nextHighSurrogate(providerId, contentType)
        while (true) {
            val now = System.currentTimeMillis()
            try {
                dao.insert(
                    StalkerRemoteIdentityEntity(
                        providerId = providerId,
                        contentType = contentType,
                        rawId = rawId,
                        surrogateId = candidate,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                return candidate
            } catch (_: SQLiteConstraintException) {
                dao.getByRawId(providerId, contentType.name, rawId)?.let { return it.surrogateId }
                candidate = nextHighSurrogate(providerId, contentType)
            }
        }
    }

    private suspend fun nextHighSurrogate(providerId: Long, contentType: ContentType): Long =
        ((dao.maxAllocatedSurrogate(providerId, contentType.name, HIGH_SURROGATE_FLOOR)
            ?: (HIGH_SURROGATE_FLOOR - 1L)) + 1L).coerceAtMost(Long.MAX_VALUE - 1L)

    private fun legacyId(providerId: Long, contentType: ContentType, rawId: String): Long {
        return stalkerStableHashId(providerId, contentType, rawId)
    }

    private companion object {
        const val HIGH_SURROGATE_FLOOR = 4_000_000_000L
    }
}
