package com.streamvault.domain.model

enum class ProgramReminderDeliveryState {
    PENDING,
    DELIVERING,
    DELIVERED,
    BLOCKED,
    FAILED,
    DISMISSED
}

data class ProgramReminder(
    val id: Long = 0,
    val providerId: Long,
    val channelId: String,
    val channelName: String,
    val programTitle: String,
    val programStartTime: Long,
    val remindAt: Long,
    val leadTimeMinutes: Int = 5,
    val isDismissed: Boolean = false,
    val notifiedAt: Long? = null,
    val exactAlarmArmed: Boolean = true,
    val deliveryState: ProgramReminderDeliveryState = ProgramReminderDeliveryState.PENDING,
    val deliveryAttemptedAt: Long? = null,
    val deliveryAttemptCount: Int = 0,
    val deliveryFailureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
