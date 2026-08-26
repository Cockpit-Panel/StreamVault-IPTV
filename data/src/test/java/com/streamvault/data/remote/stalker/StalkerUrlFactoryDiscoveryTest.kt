package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StalkerUrlFactoryDiscoveryTest {

    @Test
    fun `explicit HTTPS and HTTP schemes are preserved for all candidates`() {
        val https = StalkerUrlFactory.loadUrlCandidates("https://portal.example.com/c/")
        val http = StalkerUrlFactory.loadUrlCandidates("http://portal.example.com/c/index.html")

        assertThat(https).containsExactly(
            "https://portal.example.com/server/load.php",
            "https://portal.example.com/portal.php"
        ).inOrder()
        assertThat(http).containsExactly(
            "http://portal.example.com/server/load.php",
            "http://portal.example.com/portal.php"
        ).inOrder()
    }

    @Test
    fun `custom base path and explicit RPC endpoint remain bounded and deterministic`() {
        val custom = StalkerUrlFactory.loadUrlCandidates("https://portal.example.com/custom/base")
        val direct = StalkerUrlFactory.loadUrlCandidates(
            "https://portal.example.com/custom/server/load.php"
        )

        // Modern Ministra API family (server/load.php) is preferred across known install
        // locations before the legacy portal.php endpoints.
        assertThat(custom).containsExactly(
            "https://portal.example.com/custom/base/server/load.php",
            "https://portal.example.com/custom/base/stalker_portal/server/load.php",
            "https://portal.example.com/custom/base/portal.php",
            "https://portal.example.com/custom/base/stalker_portal/portal.php"
        ).inOrder()
        assertThat(custom.size).isAtMost(8)
        assertThat(direct).containsExactly(
            "https://portal.example.com/custom/server/load.php"
        )
    }

    @Test
    fun `bare portal bases are detected for redirect-hint probing`() {
        assertThat(StalkerUrlFactory.isBarePortalBase("http://portal.example.com")).isTrue()
        assertThat(StalkerUrlFactory.isBarePortalBase("http://portal.example.com/")).isTrue()
        assertThat(StalkerUrlFactory.isBarePortalBase("https://portal.example.com/custom/base")).isTrue()

        assertThat(StalkerUrlFactory.isBarePortalBase("https://portal.example.com/c")).isFalse()
        assertThat(StalkerUrlFactory.isBarePortalBase("https://portal.example.com/c/")).isFalse()
        assertThat(StalkerUrlFactory.isBarePortalBase("https://portal.example.com/c/index.html")).isFalse()
        assertThat(StalkerUrlFactory.isBarePortalBase("https://portal.example.com/server/load.php")).isFalse()
        assertThat(StalkerUrlFactory.isBarePortalBase("https://portal.example.com/stalker_portal/portal.php")).isFalse()
    }
}
