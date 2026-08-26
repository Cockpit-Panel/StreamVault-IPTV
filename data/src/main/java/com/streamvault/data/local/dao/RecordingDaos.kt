package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamvault.data.local.entity.RecordingRunEntity
import com.streamvault.data.local.entity.RecordingRunWithSchedule
import com.streamvault.data.local.entity.RecordingScheduleEntity
import com.streamvault.data.local.entity.RecordingStorageEntity
import com.streamvault.data.local.entity.ProgramReminderEntity
import com.streamvault.domain.model.ProgramReminderDeliveryState
import com.streamvault.domain.model.RecordingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: RecordingScheduleEntity): Long

    @Update
    suspend fun update(schedule: RecordingScheduleEntity)

    @Query("SELECT * FROM recording_schedules WHERE id = :id")
    suspend fun getById(id: Long): RecordingScheduleEntity?

    @Query("DELETE FROM recording_schedules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RecordingRunDao {
    @Query("SELECT id FROM recording_runs WHERE provider_id = :providerId")
    suspend fun getIdsByProvider(providerId: Long): List<String>

    @Query(
        """
        SELECT
            rr.id,
            rr.schedule_id,
            rr.provider_id,
            rr.channel_id,
            rr.channel_name,
            rr.stream_url,
            rr.program_title,
            rr.scheduled_start_ms,
            rr.scheduled_end_ms,
            rr.recurrence,
            rr.recurring_rule_id,
            rr.status,
            rr.source_type,
            rr.output_uri,
            rr.output_display_path,
            rr.bytes_written,
            rr.average_throughput_bps,
            rr.retry_count,
            rr.last_progress_at_ms,
            rr.failure_category,
            rr.failure_reason,
            rr.terminal_at_ms,
            rr.schedule_enabled,
            rr.exact_alarm_armed,
            rr.priority
        FROM recording_runs rr
        ORDER BY rr.scheduled_start_ms DESC, rr.created_at DESC
        """
    )
    fun observeAll(): Flow<List<RecordingRunWithSchedule>>

    @Query("SELECT * FROM recording_runs WHERE id = :id")
    suspend fun getById(id: String): RecordingRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: RecordingRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(runs: List<RecordingRunEntity>)

    @Update
    suspend fun update(run: RecordingRunEntity)

    @Query("UPDATE recording_runs SET exact_alarm_armed = :armed, updated_at = :updatedAt WHERE id = :id")
    suspend fun setExactAlarmArmed(id: String, armed: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM recording_runs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM recording_runs")
    suspend fun deleteAll()

    @Query("SELECT * FROM recording_runs WHERE status = :status ORDER BY scheduled_start_ms ASC")
    suspend fun getByStatus(status: RecordingStatus): List<RecordingRunEntity>

    @Query(
        """
        SELECT * FROM recording_runs
        WHERE (status = 'SCHEDULED' OR status = 'RECORDING')
          AND scheduled_start_ms < :windowEndMs
          AND scheduled_end_ms > :windowStartMs
        ORDER BY scheduled_start_ms ASC
        """
    )
    suspend fun getOverlapping(windowStartMs: Long, windowEndMs: Long): List<RecordingRunEntity>

    @Query("SELECT * FROM recording_runs WHERE alarm_start_at_ms IS NOT NULL AND status = 'SCHEDULED'")
    suspend fun getAlarmManagedScheduledRuns(): List<RecordingRunEntity>

    @Query("SELECT * FROM recording_runs WHERE status = 'RECORDING'")
    suspend fun getRecordingRuns(): List<RecordingRunEntity>

    @Query(
        """
        SELECT * FROM recording_runs
        WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')
          AND terminal_at_ms IS NOT NULL
          AND terminal_at_ms < :thresholdMs
        ORDER BY terminal_at_ms ASC
        """
    )
    suspend fun getExpiredRuns(thresholdMs: Long): List<RecordingRunEntity>

    @Query(
        """
        SELECT * FROM recording_runs
        WHERE recurring_rule_id = :recurringRuleId
          AND status = 'SCHEDULED'
        """
    )
    suspend fun getScheduledByRecurringRuleId(recurringRuleId: String): List<RecordingRunEntity>

    @Query(
        """
        UPDATE recording_runs
        SET bytes_written = :bytesWritten,
            average_throughput_bps = :averageThroughputBytesPerSecond,
            retry_count = MAX(retry_count, :retryCount),
            last_progress_at_ms = :lastProgressAtMs,
            updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: String,
        bytesWritten: Long,
        averageThroughputBytesPerSecond: Long,
        retryCount: Int,
        lastProgressAtMs: Long,
        updatedAt: Long
    )
}

@Dao
interface RecordingStorageDao {
    @Query("SELECT * FROM recording_storage WHERE id = 1")
    fun observe(): Flow<RecordingStorageEntity?>

    @Query("SELECT * FROM recording_storage WHERE id = 1")
    suspend fun get(): RecordingStorageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(storage: RecordingStorageEntity)
}

@Dao
interface ProgramReminderDao {
    @Query("SELECT id FROM program_reminders WHERE provider_id = :providerId")
    suspend fun getIdsByProvider(providerId: Long): List<Long>

    @Query(
        """
        SELECT * FROM program_reminders
        WHERE is_dismissed = 0
        ORDER BY remind_at ASC, program_start_time ASC
        """
    )
        fun observeUpcoming(): Flow<List<ProgramReminderEntity>>

    @Query(
        """
        SELECT * FROM program_reminders
        WHERE provider_id = :providerId
          AND channel_id = :channelId
          AND program_title = :programTitle
          AND program_start_time = :programStartTime
        LIMIT 1
        """
    )
    suspend fun getByProgram(
        providerId: Long,
        channelId: String,
        programTitle: String,
        programStartTime: Long
    ): ProgramReminderEntity?

    @Query("SELECT * FROM program_reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProgramReminderEntity?

    @Query(
        """
        SELECT * FROM program_reminders
        WHERE is_dismissed = 0
          AND notified_at IS NULL
          AND program_start_time >= :activeAfter
        ORDER BY remind_at ASC
        """
    )
    suspend fun getPendingActive(activeAfter: Long): List<ProgramReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ProgramReminderEntity): Long

    @Update
    suspend fun update(reminder: ProgramReminderEntity)

    @Query("UPDATE program_reminders SET exact_alarm_armed = :armed WHERE id = :id")
    suspend fun setExactAlarmArmed(id: Long, armed: Boolean)

    @Query(
        """
        UPDATE program_reminders
        SET delivery_state = 'DELIVERING',
            delivery_attempt_token = :attemptToken,
            delivery_attempted_at = :attemptedAt,
            delivery_attempt_count = delivery_attempt_count + 1,
            delivery_failure_reason = NULL,
            exact_alarm_armed = 0
        WHERE id = :id
          AND is_dismissed = 0
          AND notified_at IS NULL
          AND delivery_state IN ('PENDING', 'BLOCKED', 'FAILED')
        """
    )
    suspend fun claimDelivery(id: Long, attemptToken: String, attemptedAt: Long): Int

    @Query(
        """
        UPDATE program_reminders
        SET delivery_state = 'DELIVERED',
            notified_at = :notifiedAt,
            delivery_attempt_token = NULL,
            delivery_failure_reason = NULL,
            exact_alarm_armed = 0
        WHERE id = :id
          AND delivery_state = 'DELIVERING'
          AND delivery_attempt_token = :attemptToken
        """
    )
    suspend fun markDelivered(id: Long, attemptToken: String, notifiedAt: Long): Int

    @Query(
        """
        UPDATE program_reminders
        SET delivery_state = :state,
            delivery_attempt_token = NULL,
            delivery_failure_reason = :reason,
            exact_alarm_armed = 0
        WHERE id = :id
          AND delivery_state = 'DELIVERING'
          AND delivery_attempt_token = :attemptToken
        """
    )
    suspend fun markDeliveryIssue(
        id: Long,
        attemptToken: String,
        state: ProgramReminderDeliveryState,
        reason: String
    ): Int

    @Query(
        """
        UPDATE program_reminders
        SET delivery_state = 'PENDING',
            delivery_attempt_token = NULL
        WHERE id = :id
          AND delivery_state = 'DELIVERING'
          AND delivery_attempt_token = :attemptToken
        """
    )
    suspend fun resetInterruptedDelivery(id: Long, attemptToken: String): Int

    @Query(
        """
        DELETE FROM program_reminders
        WHERE provider_id = :providerId
          AND channel_id = :channelId
          AND program_title = :programTitle
          AND program_start_time = :programStartTime
        """
    )
    suspend fun deleteByProgram(
        providerId: Long,
        channelId: String,
        programTitle: String,
        programStartTime: Long
    )

    @Query("DELETE FROM program_reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM program_reminders WHERE program_start_time < :beforeTime")
    suspend fun deleteExpired(beforeTime: Long): Int
}
