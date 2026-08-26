package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProgramReminderDao
import com.streamvault.data.local.entity.ProgramReminderEntity
import com.streamvault.data.manager.reminder.ProgramReminderAlarmScheduler
import com.streamvault.data.manager.reminder.ProgramReminderNotifier
import com.streamvault.data.manager.reminder.ReminderNotificationResult
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ProgramReminder
import com.streamvault.domain.model.ProgramReminderDeliveryState
import com.streamvault.domain.model.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProgramReminderManagerImplTest {

    private val dao: ProgramReminderDao = mock()
    private val alarmScheduler: ProgramReminderAlarmScheduler = mock()
    private val notifier: ProgramReminderNotifier = mock()

    private val manager = ProgramReminderManagerImpl(
        programReminderDao = dao,
        alarmScheduler = alarmScheduler,
        notifier = notifier
    )

    init {
        whenever(alarmScheduler.canScheduleExactAlarms()).thenReturn(true)
        whenever(alarmScheduler.schedule(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(Result.success(Unit))
        whenever(notifier.showReminder(org.mockito.kotlin.any())).thenReturn(ReminderNotificationResult.Accepted)
        runBlocking {
            whenever(dao.claimDelivery(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()))
                .thenReturn(1)
            whenever(dao.markDelivered(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()))
                .thenReturn(1)
            whenever(
                dao.markDeliveryIssue(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenReturn(1)
            whenever(dao.resetInterruptedDelivery(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
                .thenReturn(1)
        }
    }

    @Test
    fun `scheduleReminder inserts reminder and schedules alarm`() = runTest {
        val now = System.currentTimeMillis()
        val program = Program(
            channelId = "bbc1",
            title = "World News",
            startTime = now + 30 * 60_000L,
            endTime = now + 60 * 60_000L,
            providerId = 7L
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", program.startTime)).thenReturn(null)
        whenever(dao.insert(org.mockito.kotlin.any())).thenReturn(42L)

        val result = manager.scheduleReminder(
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            program = program
        )

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val inserted = argumentCaptor<ProgramReminderEntity>()
        verify(dao).insert(inserted.capture())
        assertThat(inserted.firstValue.exactAlarmArmed).isFalse()
        verify(alarmScheduler).schedule(eq(42L), org.mockito.kotlin.any())
        verify(dao).setExactAlarmArmed(42L, true)
    }

    @Test
    fun `scheduleReminder fails when exact alarms are unavailable`() = runTest {
        val now = System.currentTimeMillis()
        val program = Program(
            channelId = "bbc1",
            title = "World News",
            startTime = now + 30 * 60_000L,
            endTime = now + 60 * 60_000L,
            providerId = 7L
        )
        whenever(alarmScheduler.canScheduleExactAlarms()).thenReturn(false)

        val result = manager.scheduleReminder(
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            program = program
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        verify(dao, never()).insert(org.mockito.kotlin.any())
        verify(alarmScheduler, never()).schedule(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `permission loss during new reminder scheduling removes unarmed row`() = runTest {
        val now = System.currentTimeMillis()
        val program = Program(
            channelId = "bbc1",
            title = "World News",
            startTime = now + 30 * 60_000L,
            endTime = now + 60 * 60_000L,
            providerId = 7L
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", program.startTime)).thenReturn(null)
        whenever(dao.insert(org.mockito.kotlin.any())).thenReturn(42L)
        whenever(alarmScheduler.schedule(eq(42L), org.mockito.kotlin.any()))
            .thenReturn(Result.error("permission revoked"))

        val result = manager.scheduleReminder(7L, "bbc1", "BBC One", program, 5)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        verify(dao).deleteById(42L)
        verify(dao, never()).setExactAlarmArmed(42L, true)
    }

    @Test
    fun `scheduleReminder keeps existing reminder when replacement alarm fails`() = runTest {
        val now = System.currentTimeMillis()
        val program = Program(
            channelId = "bbc1",
            title = "World News",
            startTime = now + 30 * 60_000L,
            endTime = now + 60 * 60_000L,
            providerId = 7L
        )
        val existing = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = program.title,
            programStartTime = program.startTime,
            remindAt = now + 10 * 60_000L,
            leadTimeMinutes = 20
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", program.startTime)).thenReturn(existing)
        whenever(alarmScheduler.schedule(eq(42L), org.mockito.kotlin.any())).thenReturn(Result.error("denied"))

        val result = manager.scheduleReminder(
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            program = program,
            leadTimeMinutes = 5
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        verify(dao, never()).update(org.mockito.kotlin.any())
        verify(alarmScheduler).schedule(42L, existing.remindAt)
        verify(dao).setExactAlarmArmed(42L, false)
        assertThat((result as Result.Error).message).contains("startup repair is pending")
    }

    @Test
    fun `replacement database failure restores prior alarm and keeps prior row`() = runTest {
        val now = System.currentTimeMillis()
        val program = Program(
            channelId = "bbc1",
            title = "World News",
            startTime = now + 30 * 60_000L,
            endTime = now + 60 * 60_000L,
            providerId = 7L
        )
        val existing = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = program.title,
            programStartTime = program.startTime,
            remindAt = now + 10 * 60_000L,
            leadTimeMinutes = 20
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", program.startTime)).thenReturn(existing)
        whenever(dao.getById(42L)).thenReturn(existing)
        doThrow(IllegalStateException("disk full")).whenever(dao)
            .update(org.mockito.kotlin.any())

        val result = manager.scheduleReminder(
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            program = program,
            leadTimeMinutes = 5
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val scheduledTimes = argumentCaptor<Long>()
        verify(alarmScheduler, org.mockito.kotlin.times(2)).schedule(eq(42L), scheduledTimes.capture())
        assertThat(scheduledTimes.allValues.last()).isEqualTo(existing.remindAt)
        verify(dao).setExactAlarmArmed(42L, true)
    }

    @Test
    fun `replacement database and rollback failures persist unarmed repair state`() = runTest {
        val now = System.currentTimeMillis()
        val program = Program(
            channelId = "bbc1",
            title = "World News",
            startTime = now + 30 * 60_000L,
            endTime = now + 60 * 60_000L,
            providerId = 7L
        )
        val existing = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = program.title,
            programStartTime = program.startTime,
            remindAt = now + 10 * 60_000L,
            leadTimeMinutes = 20
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", program.startTime)).thenReturn(existing)
        whenever(dao.getById(42L)).thenReturn(existing)
        doThrow(IllegalStateException("disk full")).whenever(dao)
            .update(org.mockito.kotlin.any())
        whenever(alarmScheduler.schedule(42L, existing.remindAt)).thenReturn(Result.error("permission revoked"))

        val result = manager.scheduleReminder(
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            program = program,
            leadTimeMinutes = 5
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("startup repair is pending")
        verify(dao).setExactAlarmArmed(42L, false)
    }

    @Test
    fun `cancelReminder deletes reminder and cancels alarm`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = 1_000L,
            remindAt = 900L
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", 1_000L)).thenReturn(reminder)

        manager.cancelReminder(
            providerId = 7L,
            channelId = "bbc1",
            programTitle = "World News",
            programStartTime = 1_000L
        )

        verify(dao).deleteById(42L)
        verify(alarmScheduler).cancel(42L)
    }

    @Test
    fun `cancelReminder keeps row when alarm cancellation fails`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = 1_000L,
            remindAt = 900L
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", 1_000L)).thenReturn(reminder)
        doThrow(IllegalStateException("alarm service unavailable")).whenever(alarmScheduler).cancel(42L)

        val result = manager.cancelReminder(7L, "bbc1", "World News", 1_000L)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        verify(dao, never()).deleteById(42L)
    }

    @Test
    fun `cancelReminder restores prior alarm when row deletion fails`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = 1_000L,
            remindAt = 900L
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", 1_000L)).thenReturn(reminder)
        whenever(dao.getById(42L)).thenReturn(reminder)
        doThrow(IllegalStateException("database unavailable")).whenever(dao).deleteById(42L)

        val result = manager.cancelReminder(7L, "bbc1", "World News", 1_000L)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val order = inOrder(alarmScheduler, dao)
        order.verify(alarmScheduler).cancel(42L)
        order.verify(dao).deleteById(42L)
        verify(alarmScheduler).schedule(42L, reminder.remindAt)
        verify(dao).setExactAlarmArmed(42L, true)
    }

    @Test
    fun `cancel rollback failure persists unarmed startup repair state`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = 1_000L,
            remindAt = 900L
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", 1_000L)).thenReturn(reminder)
        whenever(dao.getById(42L)).thenReturn(reminder)
        doThrow(IllegalStateException("database unavailable")).whenever(dao).deleteById(42L)
        whenever(alarmScheduler.schedule(42L, reminder.remindAt)).thenReturn(Result.error("permission revoked"))

        val result = manager.cancelReminder(7L, "bbc1", "World News", 1_000L)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("startup repair is pending")
        verify(dao).setExactAlarmArmed(42L, false)
    }

    @Test
    fun `cancel treats delete exception as success when row is already gone`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = 1_000L,
            remindAt = 900L
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", 1_000L)).thenReturn(reminder)
        whenever(dao.getById(42L)).thenReturn(null)
        doThrow(IllegalStateException("completion acknowledgement lost")).whenever(dao).deleteById(42L)

        val result = manager.cancelReminder(7L, "bbc1", "World News", 1_000L)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(alarmScheduler, never()).schedule(42L, reminder.remindAt)
    }

    @Test
    fun `restoreScheduledReminders delivers overdue reminder immediately`() = runTest {
        val nowBeforeRestore = System.currentTimeMillis()
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = nowBeforeRestore + 5 * 60_000L,
            remindAt = nowBeforeRestore - 60_000L
        )
        whenever(dao.getPendingActive(org.mockito.kotlin.any())).thenReturn(listOf(reminder))
        whenever(dao.getById(42L)).thenReturn(reminder)

        manager.restoreScheduledReminders()

        verify(notifier).showReminder(reminder)
        verify(dao).markDelivered(eq(42L), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(alarmScheduler, never()).schedule(eq(42L), org.mockito.kotlin.any())
    }

    @Test
    fun `restoreScheduledReminders persists unarmed state when permission is unavailable`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = System.currentTimeMillis() + 5 * 60_000L,
            remindAt = System.currentTimeMillis() + 60_000L
        )
        whenever(alarmScheduler.canScheduleExactAlarms()).thenReturn(false)
        whenever(dao.getPendingActive(org.mockito.kotlin.any())).thenReturn(listOf(reminder))

        manager.restoreScheduledReminders()

        verify(dao).setExactAlarmArmed(42L, false)
        verify(alarmScheduler, never()).schedule(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `restoreScheduledReminders continues after one reminder fails to reschedule`() = runTest {
        val now = System.currentTimeMillis()
        val first = ProgramReminderEntity(
            id = 41L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = now + 5 * 60_000L,
            remindAt = now + 60_000L
        )
        val second = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc2",
            channelName = "BBC Two",
            programTitle = "Documentary",
            programStartTime = now + 10 * 60_000L,
            remindAt = now + 120_000L
        )
        whenever(dao.getPendingActive(org.mockito.kotlin.any())).thenReturn(listOf(first, second))
        whenever(alarmScheduler.schedule(41L, first.remindAt)).thenReturn(Result.error("denied"))

        manager.restoreScheduledReminders()

        verify(alarmScheduler).schedule(41L, first.remindAt)
        verify(alarmScheduler).schedule(42L, second.remindAt)
    }

    @Test
    fun `startup repair restores persisted time after process death during replacement`() = runTest {
        val now = System.currentTimeMillis()
        val persistedPrior = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = now + 30 * 60_000L,
            remindAt = now + 10 * 60_000L,
            leadTimeMinutes = 20
        )
        val replacementTime = now + 25 * 60_000L
        whenever(dao.getPendingActive(org.mockito.kotlin.any())).thenReturn(listOf(persistedPrior))

        // The prior process scheduled the replacement and died before committing Room.
        alarmScheduler.schedule(42L, replacementTime)
        manager.restoreScheduledReminders()

        val times = argumentCaptor<Long>()
        verify(alarmScheduler, org.mockito.kotlin.times(2)).schedule(eq(42L), times.capture())
        assertThat(times.allValues).containsExactly(replacementTime, persistedPrior.remindAt).inOrder()
        verify(dao).setExactAlarmArmed(42L, true)
    }

    @Test
    fun `startup repair restores persisted alarm after process death during cancellation`() = runTest {
        val now = System.currentTimeMillis()
        val persistedReminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = now + 30 * 60_000L,
            remindAt = now + 10 * 60_000L
        )
        whenever(dao.getPendingActive(org.mockito.kotlin.any())).thenReturn(listOf(persistedReminder))

        // The prior process cancelled AlarmManager and died before deleting Room.
        alarmScheduler.cancel(42L)
        manager.restoreScheduledReminders()

        verify(alarmScheduler).schedule(42L, persistedReminder.remindAt)
        verify(dao).setExactAlarmArmed(42L, true)
    }

    @Test
    fun `deliverReminder notifies once and marks reminder notified`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = System.currentTimeMillis() + 5 * 60_000L,
            remindAt = System.currentTimeMillis()
        )
        whenever(dao.getById(42L)).thenReturn(reminder)

        manager.deliverReminder(42L)

        verify(notifier).showReminder(reminder)
        verify(dao).claimDelivery(eq(42L), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(dao).markDelivered(eq(42L), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `deliverReminder persists blocked reason when notification permission is revoked`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = System.currentTimeMillis() + 5 * 60_000L,
            remindAt = System.currentTimeMillis()
        )
        whenever(dao.getById(42L)).thenReturn(reminder)
        whenever(notifier.showReminder(reminder))
            .thenReturn(ReminderNotificationResult.Blocked("Notifications are disabled"))

        manager.deliverReminder(42L)

        verify(dao).markDeliveryIssue(
            eq(42L),
            org.mockito.kotlin.any(),
            eq(ProgramReminderDeliveryState.BLOCKED),
            eq("Notifications are disabled")
        )
    }

    @Test
    fun `deliverReminder persists channel disabled as blocked`() = runTest {
        val reminder = deliveryReminder()
        whenever(dao.getById(42L)).thenReturn(reminder)
        whenever(notifier.showReminder(reminder))
            .thenReturn(ReminderNotificationResult.Blocked("Program reminders channel is disabled"))

        manager.deliverReminder(42L)

        verify(dao).markDeliveryIssue(
            eq(42L),
            org.mockito.kotlin.any(),
            eq(ProgramReminderDeliveryState.BLOCKED),
            eq("Program reminders channel is disabled")
        )
    }

    @Test
    fun `deliverReminder persists notifier exception as failed`() = runTest {
        val reminder = deliveryReminder()
        whenever(dao.getById(42L)).thenReturn(reminder)
        doThrow(IllegalStateException("notification service failed"))
            .whenever(notifier).showReminder(reminder)

        manager.deliverReminder(42L)

        verify(dao).markDeliveryIssue(
            eq(42L),
            org.mockito.kotlin.any(),
            eq(ProgramReminderDeliveryState.FAILED),
            eq("Unable to show program reminder.")
        )
    }

    @Test
    fun `process death after notify reconciles visible stable notification without reposting`() = runTest {
        val reminder = deliveryReminder().copy(
            deliveryState = ProgramReminderDeliveryState.DELIVERING,
            deliveryAttemptToken = "prior-attempt",
            deliveryAttemptedAt = System.currentTimeMillis() - 1_000L,
            deliveryAttemptCount = 1
        )
        whenever(dao.getById(42L)).thenReturn(reminder)
        whenever(notifier.isReminderVisible(42L)).thenReturn(true)

        manager.deliverReminder(42L)

        verify(dao).markDelivered(eq(42L), eq("prior-attempt"), org.mockito.kotlin.any())
        verify(notifier, never()).showReminder(org.mockito.kotlin.any())
        verify(dao, never()).claimDelivery(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `process death before notify retries interrupted claim once`() = runTest {
        val interrupted = deliveryReminder().copy(
            deliveryState = ProgramReminderDeliveryState.DELIVERING,
            deliveryAttemptToken = "prior-attempt",
            deliveryAttemptedAt = System.currentTimeMillis() - 1_000L,
            deliveryAttemptCount = 1
        )
        val pending = interrupted.copy(
            deliveryState = ProgramReminderDeliveryState.PENDING,
            deliveryAttemptToken = null
        )
        whenever(dao.getById(42L)).thenReturn(interrupted, pending)
        whenever(notifier.isReminderVisible(42L)).thenReturn(false)

        manager.deliverReminder(42L)

        verify(dao).resetInterruptedDelivery(42L, "prior-attempt")
        verify(dao).claimDelivery(eq(42L), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(notifier).showReminder(pending)
        verify(dao).markDelivered(eq(42L), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `already delivered reminder is deduplicated`() = runTest {
        val reminder = deliveryReminder().copy(
            notifiedAt = System.currentTimeMillis(),
            deliveryState = ProgramReminderDeliveryState.DELIVERED
        )
        whenever(dao.getById(42L)).thenReturn(reminder)

        manager.deliverReminder(42L)

        verify(notifier, never()).showReminder(org.mockito.kotlin.any())
        verify(dao, never()).claimDelivery(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `reboot recovery immediately retries a due reminder`() = runTest {
        val reminder = deliveryReminder().copy(remindAt = System.currentTimeMillis() - 1_000L)
        whenever(dao.getPendingActive(org.mockito.kotlin.any())).thenReturn(listOf(reminder))
        whenever(dao.getById(42L)).thenReturn(reminder)

        manager.restoreScheduledReminders()

        verify(notifier).showReminder(reminder)
        verify(alarmScheduler, never()).schedule(eq(42L), org.mockito.kotlin.any())
    }

    @Test
    fun `cancellation while claiming delivery propagates without notification`() = runTest {
        val reminder = deliveryReminder()
        whenever(dao.getById(42L)).thenReturn(reminder)
        doThrow(CancellationException("cancelled")).whenever(dao)
            .claimDelivery(eq(42L), org.mockito.kotlin.any(), org.mockito.kotlin.any())

        val thrown = try {
            manager.deliverReminder(42L)
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        verify(notifier, never()).showReminder(org.mockito.kotlin.any())
    }

    @Test
    fun `deliverReminder skips stale reminders and dismisses them`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = System.currentTimeMillis() - 3 * 60_000L,
            remindAt = System.currentTimeMillis() - 8 * 60_000L
        )
        whenever(dao.getById(42L)).thenReturn(reminder)

        manager.deliverReminder(42L)

        verify(notifier, never()).showReminder(org.mockito.kotlin.any())
        val updatedCaptor = argumentCaptor<ProgramReminderEntity>()
        verify(dao).update(updatedCaptor.capture())
        assertThat(updatedCaptor.firstValue.isDismissed).isTrue()
        assertThat(updatedCaptor.firstValue.notifiedAt).isNull()
        assertThat(updatedCaptor.firstValue.deliveryState)
            .isEqualTo(ProgramReminderDeliveryState.DISMISSED)
    }

    @Test
    fun `observeUpcomingReminders drops expired reminders when only time advances`() = runTest {
        var now = 10_000L
        val reminders = MutableStateFlow(
            listOf(
                ProgramReminderEntity(
                    id = 42L,
                    providerId = 7L,
                    channelId = "bbc1",
                    channelName = "BBC One",
                    programTitle = "World News",
                    programStartTime = now + 1_000L,
                    remindAt = now - 30_000L
                )
            )
        )
        val upcomingTimeFlow = MutableStateFlow(now)
        val flowDao = object : ProgramReminderDao {
            override fun observeUpcoming(): Flow<List<ProgramReminderEntity>> = reminders

            override suspend fun getIdsByProvider(providerId: Long): List<Long> = error("unused")

            override suspend fun getByProgram(
                providerId: Long,
                channelId: String,
                programTitle: String,
                programStartTime: Long
            ): ProgramReminderEntity? = error("unused")

            override suspend fun getById(id: Long): ProgramReminderEntity? = error("unused")

            override suspend fun getPendingActive(activeAfter: Long): List<ProgramReminderEntity> =
                error("unused")

            override suspend fun insert(reminder: ProgramReminderEntity): Long = error("unused")

            override suspend fun update(reminder: ProgramReminderEntity) = error("unused")

            override suspend fun setExactAlarmArmed(id: Long, armed: Boolean) = error("unused")

            override suspend fun claimDelivery(id: Long, attemptToken: String, attemptedAt: Long): Int =
                error("unused")

            override suspend fun markDelivered(id: Long, attemptToken: String, notifiedAt: Long): Int =
                error("unused")

            override suspend fun markDeliveryIssue(
                id: Long,
                attemptToken: String,
                state: ProgramReminderDeliveryState,
                reason: String
            ): Int = error("unused")

            override suspend fun resetInterruptedDelivery(id: Long, attemptToken: String): Int =
                error("unused")

            override suspend fun deleteByProgram(
                providerId: Long,
                channelId: String,
                programTitle: String,
                programStartTime: Long
            ) = error("unused")

            override suspend fun deleteById(id: Long) = error("unused")

            override suspend fun deleteExpired(beforeTime: Long): Int = error("unused")
        }
        val timedManager = ProgramReminderManagerImpl.forTesting(
            programReminderDao = flowDao,
            alarmScheduler = alarmScheduler,
            notifier = notifier,
            nowProvider = { now },
            upcomingTimeFlow = upcomingTimeFlow
        )
        val initial = timedManager.observeUpcomingReminders().first()
        assertThat(initial.map(ProgramReminder::id)).containsExactly(42L)

        now += 2_000L
        upcomingTimeFlow.value = now

        val expired = timedManager.observeUpcomingReminders().first()
        assertThat(expired).isEmpty()
    }

    private fun deliveryReminder() = ProgramReminderEntity(
        id = 42L,
        providerId = 7L,
        channelId = "bbc1",
        channelName = "BBC One",
        programTitle = "World News",
        programStartTime = System.currentTimeMillis() + 5 * 60_000L,
        remindAt = System.currentTimeMillis()
    )
}
