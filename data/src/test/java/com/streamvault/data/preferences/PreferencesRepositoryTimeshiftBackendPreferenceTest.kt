package com.streamvault.data.preferences

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.TimeshiftBackendPreference
import org.junit.Test

class PreferencesRepositoryTimeshiftBackendPreferenceTest {

    @Test
    fun `parseTimeshiftBackendPreference defaults to automatic when missing`() {
        assertThat(parseTimeshiftBackendPreference(null))
            .isEqualTo(TimeshiftBackendPreference.AUTOMATIC)
    }

    @Test
    fun `parseTimeshiftBackendPreference defaults to automatic when saved value is invalid`() {
        assertThat(parseTimeshiftBackendPreference("RAMDISK"))
            .isEqualTo(TimeshiftBackendPreference.AUTOMATIC)
    }

    @Test
    fun `parseTimeshiftBackendPreference restores saved mode`() {
        assertThat(parseTimeshiftBackendPreference("STORAGE"))
            .isEqualTo(TimeshiftBackendPreference.STORAGE)
        assertThat(parseTimeshiftBackendPreference("MEMORY"))
            .isEqualTo(TimeshiftBackendPreference.MEMORY)
    }
}
