package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import org.junit.Test

class ProviderInputSanitizerTest {

    @Test
    fun `normalizeProviderName trims collapses whitespace and strips controls`() {
        val value = ProviderInputSanitizer.normalizeProviderName("  My\n\tProvider\u0000   Name  ")

        assertThat(value).isEqualTo("MyProvider Name")
    }

    @Test
    fun `sanitizeUrlForEditing truncates and preserves visible characters`() {
        val longInput = "https://example.com/" + "a".repeat(ProviderInputSanitizer.MAX_URL_LENGTH + 50)

        val sanitized = ProviderInputSanitizer.sanitizeUrlForEditing(longInput)

        assertThat(sanitized.length).isEqualTo(ProviderInputSanitizer.MAX_URL_LENGTH)
        assertThat(sanitized).startsWith("https://example.com/")
    }

    @Test
    fun `validateUrl rejects whitespace`() {
        assertThat(ProviderInputSanitizer.validateUrl("https://example.com/a b.m3u"))
            .isEqualTo("URLs cannot contain spaces or line breaks.")
        assertThat(ProviderInputSanitizer.validateUrl("https://example.com/a.m3u")).isNull()
    }

    @Test
    fun `resolveUrlProtocol keeps explicit scheme unchanged`() = runBlocking {
        val value = ProviderInputSanitizer.resolveUrlProtocol("https://example.com:8080")

        assertThat(value).isEqualTo("https://example.com:8080")
    }

    @Test
    fun `resolveUrlProtocol falls back to http for bare host when https probe fails`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val host = "127.0.0.1:$port"

        val value = ProviderInputSanitizer.resolveUrlProtocol(host)

        assertThat(value).isEqualTo("http://$host")
    }

    @Test
    fun `resolveUrlProtocol keeps non-http schemes unchanged without probing`() = runBlocking {
        assertThat(ProviderInputSanitizer.resolveUrlProtocol("file:///storage/emulated/0/playlist.m3u"))
            .isEqualTo("file:///storage/emulated/0/playlist.m3u")
        assertThat(ProviderInputSanitizer.resolveUrlProtocol("content://downloads/public_downloads/1"))
            .isEqualTo("content://downloads/public_downloads/1")
    }

    @Test
    fun `httpsProbeAccepts only accepts 2xx responses`() {
        assertThat(ProviderInputSanitizer.httpsProbeAccepts(200)).isTrue()
        assertThat(ProviderInputSanitizer.httpsProbeAccepts(204)).isTrue()
        assertThat(ProviderInputSanitizer.httpsProbeAccepts(299)).isTrue()
        // 3xx (redirect elsewhere) and 4xx/5xx fall back to HTTP.
        assertThat(ProviderInputSanitizer.httpsProbeAccepts(301)).isFalse()
        assertThat(ProviderInputSanitizer.httpsProbeAccepts(302)).isFalse()
        assertThat(ProviderInputSanitizer.httpsProbeAccepts(401)).isFalse()
        assertThat(ProviderInputSanitizer.httpsProbeAccepts(404)).isFalse()
        assertThat(ProviderInputSanitizer.httpsProbeAccepts(500)).isFalse()
    }
}