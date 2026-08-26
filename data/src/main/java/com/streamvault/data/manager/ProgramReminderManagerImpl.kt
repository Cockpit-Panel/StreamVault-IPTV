package com.streamvault.data.manager

import com.streamvault.data.local.dao.ProgramReminderDao
import com.streamvault.data.local.entity.ProgramReminderEntity
import com.streamvault.data.manager.reminder.ProgramReminderAlarmScheduler
import com.streamvault.data.manager.reminder.ProgramReminderNotifier
import com.streamvault.data.manager.reminder.ReminderNotificationResult
import com.streamvault.domain.manager.ProgramReminderManager
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ProgramReminder
import com.streamvault.domain.model.ProgramReminderDeliveryState
import com.streamvault.domain.model.Result
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val UPCOMING_REMINDER_REFRESH_MS = 15_000L

@Singleton
class ProgramReminderManagerImpl private constructor(
    private val programReminderDao: ProgramReminderDao,
    private val alarmScheduler: ProgramReminderAlarmScheduler,
    private val notifier: ProgramReminderNotifier,
    private val nowProvider: () -> Long,
    private val upcomingTimeFlow: Flow<Long>
) : ProgramReminderManager {
    private val mutationMutex = Mutex()

    @Inject
    constructor(
        programReminderDao: ProgramReminderDao,
        alarmScheduler: ProgramReminderAlarmScheduler,
        notifier: ProgramReminderNotifier
    ) : this(
        programReminderDao = programReminderDao,
        alarmScheduler = alarmScheduler,
        notifier = notifier,
        nowProvider = System::currentTimeMillis,
        upcomingTimeFlow = upcomingReminderTimeFlow()
    )

    internal companion object {
        const val REMINDER_STALE_GRACE_MS = 2 * 60_000L
        const val STALE_REMINDER_REASON =
            "The program started before its reminder could be delivered."

        fun forTesting(
            programReminderDao: ProgramReminderDao,
            alarmScheduler: ProgramReminderAlarmScheduler,
            notifier: ProgramReminderNotifier,
            nowProvider: () -> Long,
            upcomingTimeFlow: Flow<Long>
        ): ProgramReminderManagerImpl = ProgramReminderManagerImpl(
            programReminderDao = programReminderDao,
            alarmScheduler = alarmScheduler,
            notifier = notifier,
            nowProvider = nowProvider,
            upcomingTimeFlow = upcomingTimeFlow
        )
    }

    override fun observeUpcomingReminders(): Flow<List<ProgramReminder>> =
        programReminderDao.observeUpcoming()
            .combine(upcomingTimeFlow) { reminders, now ->
                reminders
                    .asSequence()
                    .filter {
                        !it.isDismissed &&
                            (
                                it.programStartTime >= now ||
                                    (
                                        it.deliveryState in setOf(
                                            ProgramReminderDeliveryState.BLOCKED,
                                            ProgramReminderDeliveryState.FAILED
                                        ) &&
                                            it.programStartTime >= now - REMINDER_STALE_GRACE_MS
                                        )
                                )
                    }
                    .map { it.asDomain() }
                    .toList()
            }
            .distinctUntilChanged()

    override suspend fun isReminderScheduled(
        providerId: Long,
        channelId: String,
        programTitle: String,
        programStartTime: Long
    ): Boolean {
        val reminder = programReminderDao.getByProgram(providerId, channelId, programTitle, programStartTime)
        return reminder != null && !reminder.isDismissed
    }

    override suspend fun scheduleReminder(
        providerId: Long,
        channelId: String,
        channelName: String,
        program: Program,
        leadTimeMinutes: Int
    ): Result<Unit> = mutationMutex.withLock {
        if (providerId <= 0L) return@withLock Result.error("Program reminders need a synced provider.")
        if (program.startTime <= nowProvider()) {
            return@withLock Result.error("This program has already started.")
        }
        if (!alarmScheduler.canScheduleExactAlarms()) {
            return@withLock Result.error(ProgramReminderAlarmScheduler.EXACT_ALARM_PERMISSION_MESSAGE)
        }

        val now = nowProvider()
        val remindAt = (program.startTime - leadTimeMinutes * 60_000L).coerceAtLeast(now + 1_000L)
        val existing = programReminderDao.getByProgram(providerId, channelId, program.title, program.startTime)
        val reminder = ProgramReminderEntity(
            id = existing?.id ?: 0L,
            providerId = providerId,
            channelId = channelId,
            channelName = channelName,
            programTitle = program.title,
            programStartTime = program.startTime,
            remindAt = remindAt,
            leadTimeMinutes = leadTimeMinutes,
            isDismissed = false,
            notifiedAt = null,
            exactAlarmArmed = existing != null,
            createdAt = existing?.createdAt ?: now
        )
        val reminderId = existing?.id ?: try {
            programReminderDao.insert(reminder)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@withLock Result.error("Could not save program reminder: ${error.message}", error)
        }
        return@withLock when (val result = alarmScheduler.schedule(reminderId, remindAt)) {
            is Result.Success -> {
                if (existing == null) {
                    try {
                        programReminderDao.setExactAlarmArmed(reminderId, true)
                        Result.success(Unit)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.error(
                            "Reminder alarm is scheduled, but its armed state could not be saved; startup reconciliation will repair it.",
                            error
                        )
                    }
                } else {
                    commitReminderReplacement(existing, reminder)
                }
            }
            is Result.Error -> {
                if (existing == null) {
                    try {
                        programReminderDao.deleteById(reminderId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (deleteError: Exception) {
                        return@withLock Result.error(
                            "${result.message}; the unarmed reminder row could not be removed and will be reconciled on startup.",
                            deleteError
                        )
                    }
                    Result.error(result.message, result.exception)
                } else {
                    restorePriorReminder(
                        existing = existing,
                        primaryMessage = result.message,
                        primaryError = result.exception
                    )
                }
            }
            Result.Loading -> {
                if (existing == null) {
                    try {
                        programReminderDao.deleteById(reminderId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The inserted row is explicitly unarmed and startup reconciliation
                        // can safely retry or remove it.
                    }
                    Result.error("Unexpected reminder scheduling state")
                } else {
                    restorePriorReminder(
                        existing = existing,
                        primaryMessage = "Unexpected reminder scheduling state",
                        primaryError = null
                    )
                }
            }
        }
    }

    override suspend fun cancelReminder(
        providerId: Long,
        channelId: String,
        programTitle: String,
        programStartTime: Long
    ): Result<Unit> = mutationMutex.withLock {
        val existing = programReminderDao.getByProgram(providerId, channelId, programTitle, programStartTime)
            ?: return@withLock Result.success(Unit)
        try {
            alarmScheduler.cancel(existing.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@withLock Result.error("Could not cancel reminder alarm; the reminder was kept.", error)
        }
        return@withLock try {
            programReminderDao.deleteById(existing.id)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (deleteError: Exception) {
            val persistedRead = try {
                programReminderDao.getById(existing.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setExactAlarmArmedBestEffort(existing.id, false)
                return@withLock Result.error(
                    "Reminder cancellation could not be verified; startup repair is pending.",
                    deleteError
                )
            }
            if (persistedRead == null) {
                Result.success(Unit)
            } else {
                restorePriorReminder(
                    existing = persistedRead,
                    primaryMessage = "Could not remove reminder after cancelling its alarm",
                    primaryError = deleteError
                )
            }
        }
    }

    override suspend fun restoreScheduledReminders() = mutationMutex.withLock {
        val now = nowProvider()
        val reminders = programReminderDao.getPendingActive(now - REMINDER_STALE_GRACE_MS)
        val canScheduleExactAlarms = alarmScheduler.canScheduleExactAlarms()
        reminders.forEach { reminder ->
            val deliveryIsDue = reminder.remindAt <= now ||
                reminder.deliveryState != ProgramReminderDeliveryState.PENDING
            if (deliveryIsDue) {
                deliverReminderLocked(reminder.id)
            } else if (!canScheduleExactAlarms) {
                setExactAlarmArmedBestEffort(reminder.id, false)
            } else {
                when (val result = alarmScheduler.schedule(reminder.id, reminder.remindAt.coerceAtLeast(now + 1_000L))) {
                    is Result.Error -> {
                        setExactAlarmArmedBestEffort(reminder.id, false)
                        android.util.Log.w("ProgramReminderManager", "Unable to restore reminder ${reminder.id}: ${result.message}")
                    }
                    is Result.Success -> setExactAlarmArmedBestEffort(reminder.id, true)
                    Result.Loading -> setExactAlarmArmedBestEffort(reminder.id, false)
                }
            }
        }
    }

    private suspend fun commitReminderReplacement(
        existing: ProgramReminderEntity,
        replacement: ProgramReminderEntity
    ): Result<Unit> = try {
        programReminderDao.update(replacement.copy(exactAlarmArmed = true))
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (updateError: Exception) {
        val persisted = try {
            programReminderDao.getById(existing.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (persisted == replacement.copy(exactAlarmArmed = true)) {
            Result.success(Unit)
        } else {
            restorePriorReminder(
                existing = existing,
                primaryMessage = "Replacement alarm was scheduled but the reminder update was not committed",
                primaryError = updateError
            )
        }
    }

    private suspend fun restorePriorReminder(
        existing: ProgramReminderEntity,
        primaryMessage: String,
        primaryError: Throwable?
    ): Result<Unit> {
        return when (val rollback = alarmScheduler.schedule(existing.id, existing.remindAt)) {
            is Result.Success -> {
                try {
                    programReminderDao.setExactAlarmArmed(existing.id, true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (stateError: Exception) {
                    return Result.error(
                        "$primaryMessage; the prior alarm was restored but its armed state could not be saved.",
                        stateError
                    )
                }
                Result.error("$primaryMessage; the prior reminder was restored.", primaryError)
            }
            is Result.Error -> {
                try {
                    programReminderDao.setExactAlarmArmed(existing.id, false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The persisted reminder still provides the source of truth for startup repair.
                }
                Result.error(
                    "$primaryMessage; the prior alarm could not be restored and startup repair is pending: ${rollback.message}",
                    rollback.exception ?: primaryError
                )
            }
            Result.Loading -> {
                setExactAlarmArmedBestEffort(existing.id, false)
                Result.error(
                    "$primaryMessage; prior-alarm restoration did not complete and startup repair is pending.",
                    primaryError
                )
            }
        }
    }

    private suspend fun setExactAlarmArmedBestEffort(id: Long, armed: Boolean) {
        try {
            programReminderDao.setExactAlarmArmed(id, armed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The row remains the source of truth and the next startup/permission
            // reconciliation will retry this diagnostic state write.
        }
    }

    suspend fun deliverReminder(reminderId: Long) = mutationMutex.withLock {
        deliverReminderLocked(reminderId)
    }

    private suspend fun deliverReminderLocked(reminderId: Long) {
        var reminder = programReminderDao.getById(reminderId) ?: return
        if (reminder.isDismissed || reminder.notifiedAt != null) return
        val now = nowProvider()
        if (now - reminder.programStartTime > REMINDER_STALE_GRACE_MS) {
            programReminderDao.update(
                reminder.copy(
                    isDismissed = true,
                    exactAlarmArmed = false,
                    deliveryState = ProgramReminderDeliveryState.DISMISSED,
                    deliveryAttemptToken = null,
                    deliveryFailureReason = STALE_REMINDER_REASON
                )
            )
            return
        }

        if (reminder.deliveryState == ProgramReminderDeliveryState.DELIVERING) {
            val interruptedToken = reminder.deliveryAttemptToken
            if (interruptedToken != null && notifier.isReminderVisible(reminder.id)) {
                programReminderDao.markDelivered(reminder.id, interruptedToken, now)
                return
            }
            if (interruptedToken != null) {
                programReminderDao.resetInterruptedDelivery(reminder.id, interruptedToken)
            } else {
                programReminderDao.update(
                    reminder.copy(
                        deliveryState = ProgramReminderDeliveryState.PENDING,
                        deliveryAttemptToken = null
                    )
                )
            }
            reminder = programReminderDao.getById(reminderId) ?: return
            if (reminder.isDismissed || reminder.notifiedAt != null) return
        }

        val attemptToken = UUID.randomUUID().toString()
        if (programReminderDao.claimDelivery(reminder.id, attemptToken, now) != 1) return
        val result = try {
            notifier.showReminder(reminder)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ReminderNotificationResult.Failed("Unable to show program reminder.", error)
        }
        when (result) {
            ReminderNotificationResult.Accepted -> {
                programReminderDao.markDelivered(reminder.id, attemptToken, now)
            }
            is ReminderNotificationResult.Blocked -> {
                programReminderDao.markDeliveryIssue(
                    reminder.id,
                    attemptToken,
                    ProgramReminderDeliveryState.BLOCKED,
                    result.reason
                )
            }
            is ReminderNotificationResult.Failed -> {
                programReminderDao.markDeliveryIssue(
                    reminder.id,
                    attemptToken,
                    ProgramReminderDeliveryState.FAILED,
                    result.reason
                )
            }
        }
    }

    private fun ProgramReminderEntity.asDomain(): ProgramReminder = ProgramReminder(
        id = id,
        providerId = providerId,
        channelId = channelId,
        channelName = channelName,
        programTitle = programTitle,
        programStartTime = programStartTime,
        remindAt = remindAt,
        leadTimeMinutes = leadTimeMinutes,
        isDismissed = isDismissed,
        notifiedAt = notifiedAt,
        exactAlarmArmed = exactAlarmArmed,
        deliveryState = deliveryState,
        deliveryAttemptedAt = deliveryAttemptedAt,
        deliveryAttemptCount = deliveryAttemptCount,
        deliveryFailureReason = deliveryFailureReason,
        createdAt = createdAt
    )

}

private fun upcomingReminderTimeFlow(refreshMs: Long = UPCOMING_REMINDER_REFRESH_MS): Flow<Long> = flow {
    emit(System.currentTimeMillis())
    while (true) {
        delay(refreshMs)
        emit(System.currentTimeMillis())
    }
}
