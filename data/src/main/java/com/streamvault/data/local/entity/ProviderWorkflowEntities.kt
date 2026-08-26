package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class ProviderWorkflowState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED
}

enum class ProviderWorkflowPhase {
    PREPARE,
    PRIMARY_CATALOG,
    CONTENT_INDEX,
    MOVIE_INDEX,
    SERIES_INDEX,
    EPG,
    FINALIZE
}

enum class ProviderWorkflowPhaseState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    SUPERSEDED
}

enum class ProviderWorkflowReason {
    STARTUP,
    PERIODIC,
    MANUAL,
    CONFIG_CHANGE,
    RECOVERY,
    REPAIR
}

/**
 * Durable coordinator state for all catalog and EPG work belonging to one provider.
 *
 * [generation] is a fencing epoch. Any operation that supersedes the active workflow increments
 * it, so an older worker can no longer renew its lease or commit a phase result.
 */
@Entity(
    tableName = "provider_workflows",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["state"]),
        Index(value = ["updated_at"]),
        Index(value = ["lease_expires_at"])
    ]
)
data class ProviderWorkflowEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "provider_id")
    val providerId: Long,
    val generation: Long,
    val state: ProviderWorkflowState,
    val reason: ProviderWorkflowReason,
    val priority: Int = 0,
    val force: Boolean = false,
    @ColumnInfo(name = "current_phase")
    val currentPhase: ProviderWorkflowPhase? = null,
    @ColumnInfo(name = "lease_token")
    val leaseToken: String? = null,
    @ColumnInfo(name = "lease_expires_at")
    val leaseExpiresAt: Long? = null,
    @ColumnInfo(name = "heartbeat_at")
    val heartbeatAt: Long? = null,
    @ColumnInfo(name = "progress_message")
    val progressMessage: String? = null,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String? = null,
    @ColumnInfo(name = "last_error_message")
    val lastErrorMessage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)

/**
 * Per-generation phase ledger. Older generations are retained as an audit trail and explicitly
 * marked [ProviderWorkflowPhaseState.SUPERSEDED] when a new generation replaces them.
 */
@Entity(
    tableName = "provider_workflow_phases",
    primaryKeys = ["provider_id", "generation", "phase"],
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["state"]),
        Index(value = ["updated_at"])
    ]
)
data class ProviderWorkflowPhaseEntity(
    @ColumnInfo(name = "provider_id")
    val providerId: Long,
    val generation: Long,
    val phase: ProviderWorkflowPhase,
    val state: ProviderWorkflowPhaseState,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    val checkpoint: String? = null,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String? = null,
    @ColumnInfo(name = "last_error_message")
    val lastErrorMessage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)
