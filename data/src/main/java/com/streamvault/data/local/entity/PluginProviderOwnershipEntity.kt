package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Durable proof that a provider was created by one exact plugin service.
 *
 * This deliberately does not use a display name or playlist URL: neither is an ownership
 * identifier and both may be shared with user-created providers.
 */
@Entity(
    tableName = "plugin_provider_ownership",
    primaryKeys = ["package_name", "service_class_name", "manifest_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"], unique = true)]
)
data class PluginProviderOwnershipEntity(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "service_class_name") val serviceClassName: String,
    @ColumnInfo(name = "manifest_id") val manifestId: String,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
