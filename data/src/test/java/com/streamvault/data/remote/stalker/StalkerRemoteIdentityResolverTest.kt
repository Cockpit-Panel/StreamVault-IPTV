package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.StalkerRemoteIdentityDao
import com.streamvault.data.local.entity.StalkerRemoteIdentityEntity
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StalkerRemoteIdentityResolverTest {
    @Test
    fun stableHashDoesNotCollapseKnownJavaHashCollision() {
        assertThat(stalkerStableHashId(7L, ContentType.MOVIE, "Aa"))
            .isNotEqualTo(stalkerStableHashId(7L, ContentType.MOVIE, "BB"))
    }

    @Test
    fun collidingLegacyHashesRemainDistinctAndReversible() = runTest {
        val dao = FakeIdentityDao()
        val resolver = StalkerRemoteIdentityResolver(dao, DirectTransactionRunner)

        // "ba" and "a\u0080" have the same Java String hash and remain unchanged by lowercase().
        val resolved = resolver.resolveAll(7L, ContentType.MOVIE, listOf("ba", "a\u0080"))

        assertThat(resolved.values.toSet()).hasSize(2)
        resolved.forEach { (rawId, surrogateId) ->
            assertThat(resolver.reverse(7L, ContentType.MOVIE, surrogateId)).isEqualTo(rawId)
        }
    }

    private object DirectTransactionRunner : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private class FakeIdentityDao : StalkerRemoteIdentityDao {
        private val rows = mutableListOf<StalkerRemoteIdentityEntity>()

        override suspend fun getByRawId(providerId: Long, contentType: String, rawId: String) =
            rows.firstOrNull { it.providerId == providerId && it.contentType.name == contentType && it.rawId == rawId }

        override suspend fun getBySurrogateId(providerId: Long, contentType: String, surrogateId: Long) =
            rows.firstOrNull { it.providerId == providerId && it.contentType.name == contentType && it.surrogateId == surrogateId }

        override suspend fun maxAllocatedSurrogate(providerId: Long, contentType: String, floor: Long): Long? =
            rows.filter { it.providerId == providerId && it.contentType.name == contentType && it.surrogateId >= floor }
                .maxOfOrNull { it.surrogateId }

        override suspend fun insert(entity: StalkerRemoteIdentityEntity) {
            check(rows.none { row ->
                row.providerId == entity.providerId && row.contentType == entity.contentType &&
                    (row.rawId == entity.rawId || row.surrogateId == entity.surrogateId)
            })
            rows += entity
        }
    }
}
