package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import org.junit.Test

class CatalogRouteResolverTest {
    @Test
    fun unifiedAndUnknownLayoutsUseVodRoute() {
        assertThat(resolveCatalogRoute(CatalogLayout.UNIFIED_VOD, Routes.MOVIES, ContentType.MOVIE, true))
            .isEqualTo(Routes.VOD)
        assertThat(resolveCatalogRoute(CatalogLayout.UNKNOWN, Routes.SERIES, ContentType.SERIES, true))
            .isEqualTo(Routes.VOD)
    }

    @Test
    fun splitLayoutWaitsForDestinationPreferenceBeforeRedirecting() {
        assertThat(resolveCatalogRoute(CatalogLayout.SPLIT, Routes.VOD, ContentType.SERIES, false))
            .isEqualTo(Routes.VOD)
        assertThat(resolveCatalogRoute(CatalogLayout.SPLIT, Routes.VOD, ContentType.SERIES, true))
            .isEqualTo(Routes.SERIES)
    }
}
