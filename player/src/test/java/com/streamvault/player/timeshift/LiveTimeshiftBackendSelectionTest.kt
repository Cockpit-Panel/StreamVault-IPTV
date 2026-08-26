package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.TimeshiftBackendPreference
import org.junit.Test

class LiveTimeshiftBackendSelectionTest {

    @Test
    fun `automatic prefers disk when storage is available`() {
        assertThat(
            resolveLiveTimeshiftBackend(
                preference = TimeshiftBackendPreference.AUTOMATIC,
                snapshotStorageAvailable = true,
                diskStorageAvailable = true
            )
        ).isEqualTo(LiveTimeshiftBackend.DISK)
    }

    @Test
    fun `automatic falls back to memory when disk capture is unavailable but snapshots can still be written`() {
        assertThat(
            resolveLiveTimeshiftBackend(
                preference = TimeshiftBackendPreference.AUTOMATIC,
                snapshotStorageAvailable = true,
                diskStorageAvailable = false
            )
        ).isEqualTo(LiveTimeshiftBackend.MEMORY)
    }

    @Test
    fun `all backends return null when snapshot storage is unavailable`() {
        assertThat(
            resolveLiveTimeshiftBackend(
                preference = TimeshiftBackendPreference.AUTOMATIC,
                snapshotStorageAvailable = false,
                diskStorageAvailable = false
            )
        ).isNull()
        assertThat(
            resolveLiveTimeshiftBackend(
                preference = TimeshiftBackendPreference.STORAGE,
                snapshotStorageAvailable = false,
                diskStorageAvailable = false
            )
        ).isNull()
        assertThat(
            resolveLiveTimeshiftBackend(
                preference = TimeshiftBackendPreference.MEMORY,
                snapshotStorageAvailable = false,
                diskStorageAvailable = false
            )
        ).isNull()
    }

    @Test
    fun `storage returns null when disk capture is unavailable`() {
        assertThat(
            resolveLiveTimeshiftBackend(
                preference = TimeshiftBackendPreference.STORAGE,
                snapshotStorageAvailable = true,
                diskStorageAvailable = false
            )
        ).isNull()
    }

    @Test
    fun `memory ignores disk capture availability when snapshots can still be written`() {
        assertThat(
            resolveLiveTimeshiftBackend(
                preference = TimeshiftBackendPreference.MEMORY,
                snapshotStorageAvailable = true,
                diskStorageAvailable = false
            )
        ).isEqualTo(LiveTimeshiftBackend.MEMORY)
    }
}
