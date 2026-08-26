package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.ProviderDeletionCleanupEntity

@Dao
interface ProviderDeletionCleanupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ProviderDeletionCleanupEntity>)

    @Query("SELECT * FROM provider_deletion_cleanup ORDER BY id LIMIT :limit")
    suspend fun getBatch(limit: Int): List<ProviderDeletionCleanupEntity>

    @Query("SELECT COUNT(*) FROM provider_deletion_cleanup WHERE provider_id = :providerId")
    suspend fun countByProvider(providerId: Long): Int

    @Query("DELETE FROM provider_deletion_cleanup WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE provider_deletion_cleanup SET attempt_count = attempt_count + 1, last_error = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String)
}
