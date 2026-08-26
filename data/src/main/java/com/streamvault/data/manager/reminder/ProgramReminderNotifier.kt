package com.streamvault.data.manager.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.streamvault.data.local.entity.ProgramReminderEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal sealed interface ReminderNotificationResult {
    data object Accepted : ReminderNotificationResult
    data class Blocked(val reason: String) : ReminderNotificationResult
    data class Failed(val reason: String, val cause: Throwable? = null) : ReminderNotificationResult
}

@Singleton
class ProgramReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    internal fun showReminder(reminder: ProgramReminderEntity): ReminderNotificationResult {
        return try {
            val notificationManager = NotificationManagerCompat.from(context)
            createChannelIfNeeded()
            val channelImportance = reminderChannelImportance()
            reminderNotificationBlockedReason(
                notificationsEnabled = notificationManager.areNotificationsEnabled(),
                channelImportance = channelImportance,
                hasPostNotificationsPermission =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
            )?.let { reason ->
                return ReminderNotificationResult.Blocked(reason)
            }
            val now = System.currentTimeMillis()
            val minutesUntilStart = ((reminder.programStartTime - now) / 60000L).coerceAtLeast(0L)
            val contentText = if (minutesUntilStart <= 0L) {
                "${reminder.channelName} is starting now."
            } else {
                "${reminder.channelName} starts in ${minutesUntilStart} min."
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Starting soon: ${reminder.programTitle}")
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(buildLaunchPendingIntent())
                .build()
            notificationManager.notify(reminderNotificationTag(reminder.id), 0, notification)
            ReminderNotificationResult.Accepted
        } catch (error: Exception) {
            ReminderNotificationResult.Failed("Unable to show program reminder.", error)
        }
    }

    internal fun isReminderVisible(reminderId: Long): Boolean = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return@runCatching false
        manager.activeNotifications.any { notification ->
            notification.tag == reminderNotificationTag(reminderId)
        }
    }.getOrDefault(false)

    private fun reminderNotificationTag(reminderId: Long): String = "program-reminder:$reminderId"

    private fun buildLaunchPendingIntent(): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Program reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts before scheduled live programs start"
        }
        manager.createNotificationChannel(channel)
    }

    private fun reminderChannelImportance(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return manager.getNotificationChannel(CHANNEL_ID)?.importance
    }

    companion object {
        internal const val CHANNEL_ID = "program-reminders"
        internal const val NOTIFICATIONS_DISABLED_REASON =
            "Notifications are disabled for program reminders."
        internal const val CHANNEL_DISABLED_REASON =
            "The Program reminders notification channel is disabled."
    }
}

internal fun reminderNotificationBlockedReason(
    notificationsEnabled: Boolean,
    channelImportance: Int?,
    hasPostNotificationsPermission: Boolean = true
): String? = when {
    !notificationsEnabled || !hasPostNotificationsPermission ->
        ProgramReminderNotifier.NOTIFICATIONS_DISABLED_REASON
    channelImportance == NotificationManager.IMPORTANCE_NONE ->
        ProgramReminderNotifier.CHANNEL_DISABLED_REASON
    else -> null
}
