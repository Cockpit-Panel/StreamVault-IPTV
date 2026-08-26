package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.ProviderConfigRevisionEntity
import com.streamvault.data.local.entity.ProviderConfigRevisionState

/** Durable state for a provider edit which has not yet been promoted. */
@Dao
interface ProviderConfigRevisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(revision: ProviderConfigRevisionEntity)

    @Query("SELECT COALESCE(MAX(revision), 0) FROM provider_config_revisions WHERE provider_id = :providerId")
    suspend fun latestRevision(providerId: Long): Long

    @Query("SELECT * FROM provider_config_revisions WHERE provider_id = :providerId AND state IN ('PENDING', 'SYNCING', 'FAILED') ORDER BY revision DESC LIMIT 1")
    suspend fun latestRecoverable(providerId: Long): ProviderConfigRevisionEntity?

    @Query("SELECT * FROM provider_config_revisions WHERE state IN ('PENDING', 'SYNCING', 'FAILED') ORDER BY provider_id, revision DESC")
    suspend fun getRecoverable(): List<ProviderConfigRevisionEntity>

    @Query("SELECT state FROM provider_config_revisions WHERE provider_id = :providerId AND revision = :revision")
    suspend fun getState(providerId: Long, revision: Long): ProviderConfigRevisionState?

    @Query("SELECT * FROM provider_config_revisions WHERE provider_id = :providerId AND revision = :revision")
    suspend fun get(providerId: Long, revision: Long): ProviderConfigRevisionEntity?

    @Query("""
        SELECT * FROM provider_config_revisions
        WHERE state IN ('PENDING', 'FAILED')
           OR (
               state = 'SYNCING'
               AND (
                   updated_at <= 0
                   OR updated_at > :now
                   OR updated_at <= :staleSyncingBefore
               )
           )
        ORDER BY provider_id, revision DESC
    """)
    suspend fun getRecoveryCandidates(
        now: Long,
        staleSyncingBefore: Long
    ): List<ProviderConfigRevisionEntity>

    @Query("UPDATE provider_config_revisions SET state = 'SYNCING', attempt_count = attempt_count + 1, last_error = NULL, updated_at = :updatedAt WHERE provider_id = :providerId AND revision = :revision AND state IN ('PENDING', 'FAILED')")
    suspend fun claimForSync(providerId: Long, revision: Long, updatedAt: Long): Int

    @Query("UPDATE provider_config_revisions SET state = 'FAILED', last_error = :error, updated_at = :updatedAt WHERE provider_id = :providerId AND revision = :revision AND state = 'SYNCING'")
    suspend fun markFailed(providerId: Long, revision: Long, error: String, updatedAt: Long): Int

    @Query("UPDATE provider_config_revisions SET state = 'PENDING', last_error = NULL, updated_at = :updatedAt WHERE provider_id = :providerId AND revision = :revision AND state = 'SYNCING'")
    suspend fun releaseForRetry(providerId: Long, revision: Long, updatedAt: Long): Int

    @Query("UPDATE provider_config_revisions SET state = 'SUPERSEDED', updated_at = :updatedAt WHERE provider_id = :providerId AND revision < :revision AND state IN ('PENDING', 'SYNCING', 'FAILED')")
    suspend fun supersedeOlder(providerId: Long, revision: Long, updatedAt: Long)

    @Query("""
        UPDATE provider_config_revisions
        SET state = 'COMMITTED', last_error = NULL, updated_at = :updatedAt
        WHERE provider_id = :providerId
          AND revision = :revision
          AND state = 'SYNCING'
          AND NOT EXISTS (
              SELECT 1 FROM provider_config_revisions newer
              WHERE newer.provider_id = :providerId AND newer.revision > :revision
          )
    """)
    suspend fun markCommitted(providerId: Long, revision: Long, updatedAt: Long): Int
}
