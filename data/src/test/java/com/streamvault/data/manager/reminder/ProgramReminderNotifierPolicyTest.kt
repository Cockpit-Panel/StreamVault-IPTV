package com.streamvault.data.manager.reminder

import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgramReminderNotifierPolicyTest {

    @Test
    fun `runtime notification denial is blocked`() {
        assertThat(
            reminderNotificationBlockedReason(
                notificationsEnabled = false,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT
            )
        ).isEqualTo(ProgramReminderNotifier.NOTIFICATIONS_DISABLED_REASON)
    }

    @Test
    fun `post notifications permission denial is blocked`() {
        assertThat(
            reminderNotificationBlockedReason(
                notificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
                hasPostNotificationsPermission = false
            )
        ).isEqualTo(ProgramReminderNotifier.NOTIFICATIONS_DISABLED_REASON)
    }

    @Test
    fun `disabled reminder channel is blocked`() {
        assertThat(
            reminderNotificationBlockedReason(
                notificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_NONE
            )
        ).isEqualTo(ProgramReminderNotifier.CHANNEL_DISABLED_REASON)
    }

    @Test
    fun `enabled app and channel allow delivery`() {
        assertThat(
            reminderNotificationBlockedReason(
                notificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT
            )
        ).isNull()
    }
}
