package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlSecurityPolicyTest {

    @Test
    fun `validateXtreamServerUrl allows http and https endpoints`() {
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("http://provider.example.com")).isNull()
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("https://provider.example.com")).isNull()
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("ftp://provider.example.com"))
            .isEqualTo("Xtream server URLs must use HTTP or HTTPS.")
    }

    @Test
    fun `validateXtreamServerUrl rejects embedded credentials and missing host`() {
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("https://user@example.com"))
            .isEqualTo("Xtream server URLs must not include embedded credentials.")
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("https:///portal"))
            .isEqualTo("Xtream server URLs must include a host.")
    }

    @Test
    fun `validateXtreamEpgUrl allows http and https endpoints`() {
        assertThat(UrlSecurityPolicy.validateXtreamEpgUrl("http://provider.example.com/xmltv.php")).isNull()
        assertThat(UrlSecurityPolicy.validateXtreamEpgUrl("https://provider.example.com/xmltv.php")).isNull()
        assertThat(UrlSecurityPolicy.validateXtreamEpgUrl("file:///storage/emulated/0/guide.xml"))
            .isEqualTo("Xtream EPG URLs must use HTTP or HTTPS.")
    }

    @Test
    fun `validatePlaylistSourceUrl allows local http and https`() {
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("file:///storage/emulated/0/playlist.m3u")).isNull()
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("https://example.com/playlist.m3u")).isNull()
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("http://example.com/playlist.m3u")).isNull()
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("ftp://example.com/playlist.m3u"))
            .isEqualTo("Playlist sources must use HTTP, HTTPS, or point to a local file.")
    }

    @Test
    fun `validatePlaylistSourceUrl rejects content uri because the importer cannot open it`() {
        // content:// URIs are accepted during the UI file-import flow but the imported data is
        // immediately copied to an internal file:// path. Storing a raw content:// URI as a
        // provider playlist source would cause every subsequent sync to fail because
        // SyncManagerM3uImporter only handles file: paths and HTTP/S via OkHttp.
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("content://downloads/public_downloads/1"))
            .isEqualTo("Playlist sources must use HTTP, HTTPS, or point to a local file.")
    }

    @Test
    fun `validatePlaylistSourceUrl rejects embedded credentials in remote urls`() {
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("https://user@example.com/get.php?username=a&password=b&type=m3u_plus"))
            .isEqualTo("Playlist sources must not include embedded credentials in the URL authority.")
    }

    @Test
    fun `stream entry urls reject newline injection`() {
        assertThat(UrlSecurityPolicy.isAllowedStreamEntryUrl("https://example.com/live.ts%0AInjected: true")).isFalse()
        assertThat(UrlSecurityPolicy.isAllowedStreamEntryUrl("https://example.com/live.ts")).isTrue()
    }

    @Test
    fun `stream entry urls reject double-encoded newline injection`() {
        // %250A -> %0A -> \n requires two-pass decode to catch
        assertThat(UrlSecurityPolicy.isAllowedStreamEntryUrl("https://example.com/live.ts%250AInjected: true")).isFalse()
        // %250D -> %0D -> \r
        assertThat(UrlSecurityPolicy.isAllowedStreamEntryUrl("https://example.com/live.ts%250DInjected: true")).isFalse()
        // Mixed-case double-encoded
        assertThat(UrlSecurityPolicy.isAllowedStreamEntryUrl("https://example.com/live.ts%250aInjected: true")).isFalse()
    }

    @Test
    fun `stream entry urls reject tab injection`() {
        // %09 (tab) can split structured log entries
        assertThat(UrlSecurityPolicy.isAllowedStreamEntryUrl("https://example.com/live.ts%09Injected: true")).isFalse()
    }

    @Test
    fun `validateOptionalEpgUrl allows http https and local files`() {
        assertThat(UrlSecurityPolicy.validateOptionalEpgUrl("")).isNull()
        assertThat(UrlSecurityPolicy.validateOptionalEpgUrl("http://epg.example.com/guide.xml")).isNull()
        assertThat(UrlSecurityPolicy.validateOptionalEpgUrl("https://epg.example.com/guide.xml")).isNull()
        assertThat(UrlSecurityPolicy.validateOptionalEpgUrl("content://downloads/public_downloads/guide.xml")).isNull()
        assertThat(UrlSecurityPolicy.validateOptionalEpgUrl("ftp://epg.example.com/guide.xml"))
            .isEqualTo("EPG URLs must use HTTP, HTTPS, or select a local file.")
    }

    @Test
    fun `sanitizeImportedAssetUrl drops unsupported schemes`() {
        assertThat(UrlSecurityPolicy.sanitizeImportedAssetUrl("ftp://example.com/logo.png")).isNull()
        assertThat(UrlSecurityPolicy.sanitizeImportedAssetUrl("https://example.com/logo.png"))
            .isEqualTo("https://example.com/logo.png")
    }

    @Test
    fun `validateXtreamServerUrl accepts bare hostname without scheme`() {
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("provider.example.com")).isNull()
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("provider.example.com:8080")).isNull()
    }

    @Test
    fun `validatePlaylistSourceUrl accepts bare hostname without scheme`() {
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("example.com/playlist.m3u")).isNull()
    }

    @Test
    fun `validateOptionalEpgUrl accepts bare hostname without scheme`() {
        assertThat(UrlSecurityPolicy.validateOptionalEpgUrl("epg.example.com/guide.xml")).isNull()
    }

    @Test
    fun `bare hostname with embedded credentials is still rejected`() {
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("user@example.com"))
            .isEqualTo("Xtream server URLs must not include embedded credentials.")
    }
}
