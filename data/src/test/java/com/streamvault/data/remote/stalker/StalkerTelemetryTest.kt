package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StalkerRequestPriority
import org.junit.Test

class StalkerTelemetryTest {
    @Test
    fun `request event never renders raw category item work or termination values`() {
        val rawUrl = "https://user:secret@example.invalid/portal.php?token=abc"
        val rawMac = "00:1A:79:AA:BB:CC"
        val rawWork = "token-cookie-signature"
        val event = StalkerTelemetry.buildRequestEvent(
            providerId = 4L,
            priority = StalkerRequestPriority.OPEN_CATEGORY,
            descriptor = StalkerRequestDescriptor(
                contentType = "MOVIE",
                action = "CATEGORY_PAGE",
                categoryKey = rawUrl,
                itemKey = rawMac,
                page = 2,
                workId = rawWork
            ),
            responseMetrics = StalkerResponseMetrics(
                items = 18,
                pages = 2,
                advertisedTotal = 900,
                truncated = true,
                terminationReason = rawUrl
            ),
            durationMillis = 42L,
            active = 1,
            queued = 0,
            concurrencyLimit = 2,
            stressCooldownUntil = 0L,
            outcome = "success",
            now = 1L
        )

        assertThat(event).doesNotContain(rawUrl)
        assertThat(event).doesNotContain(rawMac)
        assertThat(event).doesNotContain(rawWork)
        assertThat(event).doesNotContain("secret")
        assertThat(event).contains("items=18")
        assertThat(event).contains("truncated=true")
    }
}
