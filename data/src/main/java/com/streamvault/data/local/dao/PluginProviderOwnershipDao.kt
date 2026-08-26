package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.streamvault.data.local.entity.PluginProviderOwnershipEntity

@Dao
interface PluginProviderOwnershipDao {
    @Query("""
        SELECT * FROM plugin_provider_ownership
        WHERE package_name = :packageName
          AND service_class_name = :serviceClassName
          AND manifest_id = :manifestId
        LIMIT 1
    """)
    suspend fun get(
        packageName: String,
        serviceClassName: String,
        manifestId: String
    ): PluginProviderOwnershipEntity?

    @Query("SELECT * FROM plugin_provider_ownership")
    suspend fun getAll(): List<PluginProviderOwnershipEntity>

    @Query("""
        SELECT * FROM plugin_provider_ownership
        WHERE package_name = :packageName
          AND service_class_name = :serviceClassName
        ORDER BY created_at ASC, manifest_id ASC
    """)
    suspend fun getByComponent(
        packageName: String,
        serviceClassName: String
    ): List<PluginProviderOwnershipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ownership: PluginProviderOwnershipEntity)

    @Query("""
        DELETE FROM plugin_provider_ownership
        WHERE package_name = :packageName
          AND service_class_name = :serviceClassName
          AND manifest_id = :manifestId
    """)
    suspend fun delete(packageName: String, serviceClassName: String, manifestId: String)

    /**
     * Atomically follows a manifest-ID rename for one installed service component.
     *
     * Package/service is Android's durable lifecycle identity. The manifest ID remains part of
     * the public plugin identity, but changing it must not briefly leave an unowned provider or
     * collide with the unique provider ownership index.
     */
    @Transaction
    suspend fun rekeyManifestId(
        ownership: PluginProviderOwnershipEntity,
        newManifestId: String
    ): PluginProviderOwnershipEntity {
        if (ownership.manifestId == newManifestId) return ownership
        delete(ownership.packageName, ownership.serviceClassName, ownership.manifestId)
        return ownership.copy(manifestId = newManifestId).also { upsert(it) }
    }
}
