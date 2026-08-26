package com.streamvault.data.preferences

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DecoderMode
import org.junit.Test

class PreferencesRepositoryDecoderModeTest {

    @Test
    fun `parseDecoderModePreference defaults to auto when missing`() {
        assertThat(parseDecoderModePreference(saved = null)).isEqualTo(DecoderMode.AUTO)
    }

    @Test
    fun `parseDecoderModePreference defaults to auto when saved value is invalid`() {
        assertThat(parseDecoderModePreference(saved = "MAGIC")).isEqualTo(DecoderMode.AUTO)
    }

    @Test
    fun `parseDecoderModePreference falls back to legacy when saved value is invalid`() {
        assertThat(parseDecoderModePreference(saved = "MAGIC", legacySaved = "COMPATIBILITY"))
            .isEqualTo(DecoderMode.COMPATIBILITY)
    }

    @Test
    fun `parseDecoderModePreference falls back to legacy decoder mode`() {
        assertThat(parseDecoderModePreference(saved = null, legacySaved = "SOFTWARE"))
            .isEqualTo(DecoderMode.SOFTWARE)
    }

    @Test
    fun `parseDecoderModePreference prefers new axis value over legacy decoder mode`() {
        assertThat(parseDecoderModePreference(saved = "HARDWARE", legacySaved = "SOFTWARE"))
            .isEqualTo(DecoderMode.HARDWARE)
    }
}
