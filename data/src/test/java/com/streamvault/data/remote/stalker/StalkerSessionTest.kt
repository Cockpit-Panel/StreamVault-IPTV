package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StalkerSessionTest {
    @Test
    fun session_expires_at_explicit_server_or_account_deadline() {
        val session = StalkerSession(
            loadUrl = "https://portal.example.com/server/load.php",
            portalReferer = "https://portal.example.com/c/",
            token = "token",
            authenticatedAtMillis = 1_000L,
            expiresAtMillis = 2_000L
        )

        assertThat(session.isExpired(1_999L)).isFalse()
        assertThat(session.isExpired(2_000L)).isTrue()
    }

    @Test
    fun session_without_server_deadline_has_bounded_maximum_age() {
        val session = StalkerSession(
            loadUrl = "https://portal.example.com/server/load.php",
            portalReferer = "https://portal.example.com/c/",
            token = "token",
            authenticatedAtMillis = 1_000L
        )

        assertThat(session.isExpired(1_000L + STALKER_SESSION_MAX_AGE_MILLIS - 1L)).isFalse()
        assertThat(session.isExpired(1_000L + STALKER_SESSION_MAX_AGE_MILLIS)).isTrue()
    }
}
