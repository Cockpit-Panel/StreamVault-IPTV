package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderConfiguration
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.provider.CapabilityResolution
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncCoordinatorTest {
    @Test
    fun `full sync is routed to the provider catalog plan`() = runTest {
        val expected = SyncOutcome(
            partial = true,
            warnings = listOf("catalog resumed"),
            stagedMutations = 42,
            continuationWork = listOf(
                SyncContinuation(
                    operation = SyncContinuationOperation.INDEX_CATALOG,
                    section = com.streamvault.domain.model.ContentType.MOVIE,
                    reason = "catalog is intentionally paged"
                )
            ),
            activation = SyncActivation.ACTIVATED_CATALOG
        )
        val coordinator = coordinatorFor(expected)

        val result = coordinator.syncFull(
            FullProviderSyncRequest(
                snapshot = snapshot(ProviderType.M3U),
                force = false,
                onProgress = null,
                trackInitialLiveOnboarding = false,
                deferProviderStateUntilCatalogCommit = false,
                afterCatalogApply = {}
            )
        )

        assertThat(result).isEqualTo(CapabilityResolution.Available(expected))
    }

    @Test
    fun `registry requires one plan for every provider type`() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogSyncPlanRegistry(
                ProviderType.entries.drop(1).map { planFor(it, SyncOutcome()) }
            )
        }
    }

    @Test
    fun `activation follow-up must declare durable continuation work`() {
        val coordinator = coordinatorFor(
            SyncOutcome(activation = SyncActivation.DEFERRED_TO_FOLLOW_UP)
        )

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                coordinator.syncFull(
                    FullProviderSyncRequest(
                        snapshot = snapshot(ProviderType.M3U),
                        force = false,
                        onProgress = null,
                        trackInitialLiveOnboarding = false,
                        deferProviderStateUntilCatalogCommit = false,
                        afterCatalogApply = {}
                    )
                )
            }
        }
    }

    @Test
    fun `accepted mutations require an activated catalog receipt`() {
        val coordinator = coordinatorFor(
            SyncOutcome(stagedMutations = 1, activation = SyncActivation.NO_CATALOG_CHANGE)
        )

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator.syncFull(fullRequest(snapshot(ProviderType.M3U))) }
        }
    }

    @Test
    fun `coordinator hands typed continuation work to the durable scheduler`() = runTest {
        val continuation = SyncContinuation(
            operation = SyncContinuationOperation.INDEX_CATALOG,
            section = com.streamvault.domain.model.ContentType.MOVIE,
            reason = "index accepted movie categories"
        )
        val scheduled = mutableListOf<SyncContinuation>()
        val outcome = SyncOutcome(
            continuationWork = listOf(continuation),
            activation = SyncActivation.DEFERRED_TO_FOLLOW_UP
        )
        val coordinator = SyncCoordinator(
            CatalogSyncPlanRegistry(ProviderType.entries.map { planFor(it, outcome) }),
            SyncContinuationScheduler { _, work -> scheduled += work }
        )

        assertThat(coordinator.syncFull(fullRequest(snapshot(ProviderType.XTREAM_CODES))))
            .isEqualTo(CapabilityResolution.Available(outcome))
        assertThat(scheduled).containsExactly(continuation)
    }

    @Test
    fun `preserving active catalog is surfaced as partial activation`() {
        assertThat(
            SyncOutcome(activation = SyncActivation.PRESERVED_ACTIVE_CATALOG)
                .requiresPartialActivation
        ).isTrue()
        assertThat(
            SyncOutcome(
                continuationWork = listOf(
                    SyncContinuation(
                        SyncContinuationOperation.INDEX_CATALOG,
                        section = com.streamvault.domain.model.ContentType.MOVIE,
                        reason = "resume persisted job"
                    )
                )
            ).requiresPartialActivation
        ).isFalse()
    }

    @Test
    fun `full and every catalog repair path share the typed activation contract for every provider`() = runTest {
        val expected = SyncOutcome(
            partial = true,
            warnings = listOf("staged page retained"),
            stagedMutations = 7,
            activation = SyncActivation.ACTIVATED_CATALOG
        )

        ProviderType.entries.forEach { type ->
            val plan = concretePlanFor(type, expected)
            assertThat(plan.syncFull(fullRequest(snapshot(type)))).isEqualTo(expected)
            catalogSections(type).forEach { section ->
                assertThat(
                    plan.syncSection(sectionRequest(snapshot(type), section))
                ).isEqualTo(CapabilityResolution.Available(expected))
            }
        }
    }

    @Test
    fun `unsupported repair sections remain explicit for every provider`() = runTest {
        val expected = SyncOutcome(partial = true, activation = SyncActivation.PRESERVED_ACTIVE_CATALOG)
        ProviderType.entries.forEach { type ->
            val plan = concretePlanFor(type, expected)
            val supported = repairSections(type).toSet()
            listOf(SyncRepairSection.LIVE, SyncRepairSection.MOVIES, SyncRepairSection.SERIES, SyncRepairSection.EPG)
                .filterNot { it in supported }
                .forEach { section ->
                    assertThat(plan.syncSection(sectionRequest(snapshot(type), section)))
                        .isInstanceOf(CapabilityResolution.Unsupported::class.java)
                }
        }
    }

    private fun coordinatorFor(fullResult: SyncOutcome): SyncCoordinator =
        SyncCoordinator(
            CatalogSyncPlanRegistry(
                ProviderType.entries.map { planFor(it, fullResult) }
            )
        )

    private fun concretePlanFor(type: ProviderType, outcome: SyncOutcome): CatalogSyncPlan = when (type) {
        ProviderType.XTREAM_CODES -> XtreamCatalogSyncPlan(
            XtreamCatalogSyncOperations(
                full = { outcome },
                live = { outcome },
                epg = { },
                movies = { outcome },
                series = { outcome },
                guide = { ProviderGuideSyncResult(emptyList(), false) }
            )
        )
        ProviderType.M3U -> M3uCatalogSyncPlan(
            M3uCatalogSyncOperations(
                full = { outcome },
                live = { outcome },
                epg = { },
                movies = { outcome },
                guide = { ProviderGuideSyncResult(emptyList(), false) }
            )
        )
        ProviderType.STALKER_PORTAL -> StalkerCatalogSyncPlan(
            StalkerCatalogSyncOperations(
                full = { outcome },
                live = { outcome },
                epg = { },
                movies = { outcome },
                series = { outcome },
                guide = { ProviderGuideSyncResult(emptyList(), false) }
            )
        )
        ProviderType.JELLYFIN -> JellyfinCatalogSyncPlan(
            JellyfinCatalogSyncOperations(
                full = { outcome },
                movies = { outcome },
                series = { outcome },
                guide = { ProviderGuideSyncResult(emptyList(), false) }
            )
        )
    }

    private fun fullRequest(snapshot: ProviderSnapshot) = FullProviderSyncRequest(
        snapshot = snapshot,
        force = false,
        onProgress = null,
        trackInitialLiveOnboarding = false,
        deferProviderStateUntilCatalogCommit = false,
        afterCatalogApply = {}
    )

    private fun sectionRequest(
        snapshot: ProviderSnapshot,
        section: SyncRepairSection = SyncRepairSection.MOVIES
    ) = SectionProviderSyncRequest(
        snapshot = snapshot,
        section = section,
        syncReason = XtreamLiveSyncReason.MANUAL_SETTINGS,
        onProgress = null
    )

    private fun catalogSections(type: ProviderType): List<SyncRepairSection> = when (type) {
        ProviderType.XTREAM_CODES -> listOf(SyncRepairSection.LIVE, SyncRepairSection.MOVIES, SyncRepairSection.SERIES)
        ProviderType.M3U -> listOf(SyncRepairSection.LIVE, SyncRepairSection.MOVIES)
        ProviderType.STALKER_PORTAL -> listOf(SyncRepairSection.LIVE, SyncRepairSection.MOVIES, SyncRepairSection.SERIES)
        ProviderType.JELLYFIN -> listOf(SyncRepairSection.MOVIES, SyncRepairSection.SERIES)
    }

    private fun repairSections(type: ProviderType): List<SyncRepairSection> = when (type) {
        ProviderType.XTREAM_CODES -> listOf(
            SyncRepairSection.LIVE,
            SyncRepairSection.MOVIES,
            SyncRepairSection.SERIES,
            SyncRepairSection.EPG
        )
        ProviderType.M3U -> listOf(SyncRepairSection.LIVE, SyncRepairSection.MOVIES, SyncRepairSection.EPG)
        ProviderType.STALKER_PORTAL -> listOf(
            SyncRepairSection.LIVE,
            SyncRepairSection.MOVIES,
            SyncRepairSection.SERIES,
            SyncRepairSection.EPG
        )
        ProviderType.JELLYFIN -> listOf(SyncRepairSection.MOVIES, SyncRepairSection.SERIES)
    }

    private fun planFor(type: ProviderType, fullResult: SyncOutcome) = LambdaCatalogSyncPlan(
        providerType = type,
        full = { fullResult },
        section = { CapabilityResolution.Available(SyncOutcome()) },
        guide = { CapabilityResolution.Available(ProviderGuideSyncResult(emptyList(), false)) }
    )

    private fun snapshot(type: ProviderType): ProviderSnapshot {
        val configuration: ProviderConfiguration = when (type) {
            ProviderType.XTREAM_CODES -> XtreamConfig("https://example.com", "user", "pass")
            ProviderType.M3U -> M3uConfig("https://example.com/list.m3u")
            ProviderType.STALKER_PORTAL -> StalkerConfig(
                portalUrl = "https://example.com",
                device = StalkerDeviceIdentity("00:11:22:33:44:55")
            )
            ProviderType.JELLYFIN -> JellyfinConfig("https://example.com", "user", "token")
        }
        return ProviderSnapshot(
            provider = Provider(id = 1L, name = "Test", type = type),
            configuration = configuration,
            configurationGeneration = 1L
        )
    }
}
