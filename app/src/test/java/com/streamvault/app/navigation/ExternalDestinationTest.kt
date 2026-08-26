package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalDestinationTest {

    @Test
    fun fromLegacyRoute_parsesSupportedRoutes() {
        assertThat(ExternalDestination.fromLegacyRoute("home"))
            .isEqualTo(ExternalDestination.Home)
        assertThat(ExternalDestination.fromLegacyRoute("provider_setup?providerId=-1&importUri="))
            .isEqualTo(ExternalDestination.ProviderSetup())
        assertThat(
            ExternalDestination.fromLegacyRoute(
                "series_detail/42?returnRoute=home"
            )
        ).isEqualTo(
            ExternalDestination.SeriesDetail(seriesId = 42L, returnRoute = "home")
        )
    }

    @Test
    fun fromLegacyRoute_rejectsUnsupportedRoutes() {
        assertThat(ExternalDestination.fromLegacyRoute("settings"))
            .isNull()
        assertThat(ExternalDestination.fromLegacyRoute("series_detail/not-a-number"))
            .isNull()
    }

    @Test
    fun fromLegacyRoute_decodesUtf8AndSkipsMalformedQueryValues() {
        assertThat(
            ExternalDestination.fromLegacyRoute(
                "provider_setup?providerId=7&importUri=https%3A%2F%2Fexample.com%2Fguide%3Fname%3DCaf%C3%A9%2BTV&bad=%zz"
            )
        ).isEqualTo(
            ExternalDestination.ProviderSetup(
                providerId = 7L,
                importUri = "https://example.com/guide?name=Café+TV"
            )
        )
    }
}
