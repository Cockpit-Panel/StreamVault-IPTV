package com.streamvault.data.manager.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager

class ProgramReminderRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                ProgramReminderRestoreWorker.enqueueOneShot(context)
            }
        }
    }
}
