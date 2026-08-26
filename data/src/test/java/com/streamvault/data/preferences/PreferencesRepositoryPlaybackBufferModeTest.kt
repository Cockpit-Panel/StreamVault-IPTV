package com.streamvault.data.preferences

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackBufferMode
import org.junit.Test

class PreferencesRepositoryPlaybackBufferModeTest {

    @Test
    fun `parsePlaybackBufferModePreference defaults to auto when missing`() {
        assertThat(parsePlaybackBufferModePreference(null)).isEqualTo(PlaybackBufferMode.AUTO)
    }

    @Test
    fun `parsePlaybackBufferModePreference defaults to auto when saved value is invalid`() {
        assertThat(parsePlaybackBufferModePreference("TINY")).isEqualTo(PlaybackBufferMode.AUTO)
    }

    @Test
    fun `parsePlaybackBufferModePreference restores saved mode`() {
        assertThat(parsePlaybackBufferModePreference("MEDIUM")).isEqualTo(PlaybackBufferMode.MEDIUM)
        assertThat(parsePlaybackBufferModePreference("LARGE")).isEqualTo(PlaybackBufferMode.LARGE)
    }
}
