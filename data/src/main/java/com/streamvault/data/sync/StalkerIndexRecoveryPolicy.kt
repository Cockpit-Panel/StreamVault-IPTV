package com.streamvault.data.sync

/** Keeps durable Stalker index failure classification out of the sync façade. */
internal object StalkerIndexRecoveryPolicy {
    fun isLikelyAuthFailure(message: String, exception: Throwable?): Boolean =
        com.streamvault.data.remote.stalker.isStalkerAuthorizationFailure(message, exception)

    fun failureState(error: Throwable, sanitize: (Throwable?) -> String): String {
        if (isLikelyAuthFailure(sanitize(error), error)) {
            return "FAILED_PERMANENT"
        }
        val message = sanitize(error).lowercase()
        return if (message.contains("not accessible") || message.contains("no accessible catalog")) {
            "FAILED_PERMANENT"
        } else {
            "FAILED_RETRYABLE"
        }
    }
}
