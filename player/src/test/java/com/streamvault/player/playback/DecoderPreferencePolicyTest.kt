package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DecoderMode
import org.junit.Test

class DecoderPreferencePolicyTest {

    @Test
    fun `auto retries with software after decoder init failure`() {
        val policy = DefaultDecoderPreferencePolicy()
        val mediaId = "channel-1"

        assertThat(policy.preferredMode(DecoderMode.AUTO, mediaId)).isEqualTo(DecoderMode.HARDWARE)
        assertThat(policy.onDecoderInitFailure(DecoderMode.AUTO, mediaId)).isEqualTo(DecoderMode.SOFTWARE)
        assertThat(policy.preferredMode(DecoderMode.AUTO, mediaId)).isEqualTo(DecoderMode.SOFTWARE)
    }

    @Test
    fun `fallback is attempted once per media id`() {
        val policy = DefaultDecoderPreferencePolicy()
        val mediaId = "channel-1"

        assertThat(policy.onDecoderInitFailure(DecoderMode.AUTO, mediaId)).isEqualTo(DecoderMode.SOFTWARE)
        assertThat(policy.onDecoderInitFailure(DecoderMode.AUTO, mediaId)).isNull()
    }

    @Test
    fun `reset restores requested hardware preference`() {
        val policy = DefaultDecoderPreferencePolicy()
        val mediaId = "channel-1"

        policy.onDecoderInitFailure(DecoderMode.AUTO, mediaId)
        assertThat(policy.preferredMode(DecoderMode.AUTO, mediaId)).isEqualTo(DecoderMode.SOFTWARE)

        policy.resetForMedia(mediaId)

        assertThat(policy.preferredMode(DecoderMode.AUTO, mediaId)).isEqualTo(DecoderMode.HARDWARE)
    }

    @Test
    fun `explicit hardware software and compatibility do not fallback again`() {
        val policy = DefaultDecoderPreferencePolicy()
        val mediaId = "channel-1"

        assertThat(policy.preferredMode(DecoderMode.HARDWARE, mediaId)).isEqualTo(DecoderMode.HARDWARE)
        assertThat(policy.preferredMode(DecoderMode.SOFTWARE, mediaId)).isEqualTo(DecoderMode.SOFTWARE)
        assertThat(policy.preferredMode(DecoderMode.COMPATIBILITY, mediaId)).isEqualTo(DecoderMode.SOFTWARE)
        assertThat(policy.onDecoderInitFailure(DecoderMode.HARDWARE, mediaId)).isNull()
        assertThat(policy.onDecoderInitFailure(DecoderMode.SOFTWARE, mediaId)).isNull()
        assertThat(policy.onDecoderInitFailure(DecoderMode.COMPATIBILITY, mediaId)).isNull()
    }

    @Test
    fun `ambiguous decoder fallback forces software when either axis can retry software`() {
        assertThat(
            shouldForceSoftwareForAmbiguousDecoderFallback(
                audioFallbackMode = DecoderMode.SOFTWARE,
                videoFallbackMode = null
            )
        ).isTrue()
        assertThat(
            shouldForceSoftwareForAmbiguousDecoderFallback(
                audioFallbackMode = null,
                videoFallbackMode = DecoderMode.SOFTWARE
            )
        ).isTrue()
        assertThat(
            shouldForceSoftwareForAmbiguousDecoderFallback(
                audioFallbackMode = DecoderMode.SOFTWARE,
                videoFallbackMode = DecoderMode.SOFTWARE
            )
        ).isTrue()
    }

    @Test
    fun `ambiguous decoder fallback does not force software without a software retry`() {
        assertThat(
            shouldForceSoftwareForAmbiguousDecoderFallback(
                audioFallbackMode = null,
                videoFallbackMode = null
            )
        ).isFalse()
        assertThat(
            shouldForceSoftwareForAmbiguousDecoderFallback(
                audioFallbackMode = DecoderMode.HARDWARE,
                videoFallbackMode = null
            )
        ).isFalse()
    }
}
