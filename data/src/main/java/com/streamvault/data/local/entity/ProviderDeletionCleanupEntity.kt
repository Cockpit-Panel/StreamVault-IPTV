package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "provider_deletion_cleanup",
    indices = [Index(value = ["provider_id", "action", "target_id"], unique = true)]
)
data class ProviderDeletionCleanupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val action: String,
    @ColumnInfo(name = "target_id") val targetId: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null
)
