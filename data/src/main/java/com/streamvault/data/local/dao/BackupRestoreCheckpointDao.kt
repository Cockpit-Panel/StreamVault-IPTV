package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.BackupRestoreCheckpointEntity

/** Durable progress for a restore whose sections span Room, DataStore, and alarms. */
@Dao
interface BackupRestoreCheckpointDao {
    @Query("SELECT * FROM backup_restore_checkpoints WHERE restore_key = :restoreKey")
    suspend fun get(restoreKey: String): BackupRestoreCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(checkpoint: BackupRestoreCheckpointEntity): Long

    @Query("""
        UPDATE backup_restore_checkpoints
        SET room_complete = :roomComplete,
            preferences_complete = :preferencesComplete,
            presets_complete = :presetsComplete,
            schedules_complete = :schedulesComplete,
            state = :state,
            last_error = :lastError,
            updated_at = :updatedAt
        WHERE restore_key = :restoreKey
    """)
    suspend fun update(
        restoreKey: String,
        roomComplete: Boolean,
        preferencesComplete: Boolean,
        presetsComplete: Boolean,
        schedulesComplete: Boolean,
        state: String,
        lastError: String?,
        updatedAt: Long
    ): Int

    @Query("UPDATE backup_restore_checkpoints SET preference_snapshot_json = :snapshotJson, updated_at = :updatedAt WHERE restore_key = :restoreKey")
    suspend fun setPreferenceSnapshot(restoreKey: String, snapshotJson: String?, updatedAt: Long): Int
}
