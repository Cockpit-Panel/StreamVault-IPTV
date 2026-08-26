package com.streamvault.data.sync

import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.provider.CapabilityResolution

/** Provider execution port implemented by the sync engine, not by the coordinator. */
internal interface CatalogSyncPlanDelegate {
    suspend fun syncXtreamFull(request: FullProviderSyncRequest): SyncOutcome
    suspend fun syncXtreamLive(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncXtreamEpg(request: SectionProviderSyncRequest)
    suspend fun syncXtreamMovies(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncXtreamSeries(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncXtreamGuide(request: ProviderGuideSyncRequest): ProviderGuideSyncResult

    suspend fun syncM3uFull(request: FullProviderSyncRequest): SyncOutcome
    suspend fun syncM3uLive(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncM3uEpg(request: SectionProviderSyncRequest)
    suspend fun syncM3uMovies(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncM3uGuide(request: ProviderGuideSyncRequest): ProviderGuideSyncResult

    suspend fun syncStalkerFull(request: FullProviderSyncRequest): SyncOutcome
    suspend fun syncStalkerLive(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncStalkerEpg(request: SectionProviderSyncRequest)
    suspend fun syncStalkerMovies(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncStalkerSeries(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncStalkerGuide(request: ProviderGuideSyncRequest): ProviderGuideSyncResult

    suspend fun syncJellyfinFull(request: FullProviderSyncRequest): SyncOutcome
    suspend fun syncJellyfinMovies(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncJellyfinSeries(request: SectionProviderSyncRequest): SyncOutcome
    suspend fun syncJellyfinGuide(request: ProviderGuideSyncRequest): ProviderGuideSyncResult
}

/** Operations supplied by the data layer to the provider-neutral plan objects. */
internal data class XtreamCatalogSyncOperations(
    val full: suspend (FullProviderSyncRequest) -> SyncOutcome,
    val live: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val epg: suspend (SectionProviderSyncRequest) -> Unit,
    val movies: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val series: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val guide: suspend (ProviderGuideSyncRequest) -> ProviderGuideSyncResult
)

internal data class M3uCatalogSyncOperations(
    val full: suspend (FullProviderSyncRequest) -> SyncOutcome,
    val live: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val epg: suspend (SectionProviderSyncRequest) -> Unit,
    val movies: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val guide: suspend (ProviderGuideSyncRequest) -> ProviderGuideSyncResult
)

internal data class StalkerCatalogSyncOperations(
    val full: suspend (FullProviderSyncRequest) -> SyncOutcome,
    val live: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val epg: suspend (SectionProviderSyncRequest) -> Unit,
    val movies: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val series: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val guide: suspend (ProviderGuideSyncRequest) -> ProviderGuideSyncResult
)

internal data class JellyfinCatalogSyncOperations(
    val full: suspend (FullProviderSyncRequest) -> SyncOutcome,
    val movies: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val series: suspend (SectionProviderSyncRequest) -> SyncOutcome,
    val guide: suspend (ProviderGuideSyncRequest) -> ProviderGuideSyncResult
)

internal class XtreamCatalogSyncPlan(
    private val operations: XtreamCatalogSyncOperations
) : CatalogSyncPlan {
    override val providerType: ProviderType = ProviderType.XTREAM_CODES

    override suspend fun syncFull(request: FullProviderSyncRequest): SyncOutcome =
        operations.full(request)

    override suspend fun syncSection(
        request: SectionProviderSyncRequest
    ): CapabilityResolution<SyncOutcome> {
        return CapabilityResolution.Available(
            when (request.section) {
                SyncRepairSection.LIVE -> operations.live(request)
                SyncRepairSection.EPG -> {
                    operations.epg(request)
                    SyncOutcome()
                }
                SyncRepairSection.MOVIES -> operations.movies(request)
                SyncRepairSection.SERIES -> operations.series(request)
            }
        )
    }

    override suspend fun syncGuide(
        request: ProviderGuideSyncRequest
    ): CapabilityResolution<ProviderGuideSyncResult> =
        CapabilityResolution.Available(operations.guide(request))
}

internal class M3uCatalogSyncPlan(
    private val operations: M3uCatalogSyncOperations
) : CatalogSyncPlan {
    override val providerType: ProviderType = ProviderType.M3U

    override suspend fun syncFull(request: FullProviderSyncRequest): SyncOutcome =
        operations.full(request)

    override suspend fun syncSection(
        request: SectionProviderSyncRequest
    ): CapabilityResolution<SyncOutcome> {
        return when (request.section) {
            SyncRepairSection.LIVE -> CapabilityResolution.Available(operations.live(request))
            SyncRepairSection.MOVIES -> CapabilityResolution.Available(operations.movies(request))
            SyncRepairSection.EPG -> {
                operations.epg(request)
                CapabilityResolution.Available(SyncOutcome())
            }
            SyncRepairSection.SERIES -> CapabilityResolution.Unsupported(
                "Series retry is unavailable for M3U providers"
            )
        }
    }

    override suspend fun syncGuide(
        request: ProviderGuideSyncRequest
    ): CapabilityResolution<ProviderGuideSyncResult> =
        CapabilityResolution.Available(operations.guide(request))
}

internal class StalkerCatalogSyncPlan(
    private val operations: StalkerCatalogSyncOperations
) : CatalogSyncPlan {
    override val providerType: ProviderType = ProviderType.STALKER_PORTAL

    override suspend fun syncFull(request: FullProviderSyncRequest): SyncOutcome =
        operations.full(request)

    override suspend fun syncSection(
        request: SectionProviderSyncRequest
    ): CapabilityResolution<SyncOutcome> {
        return CapabilityResolution.Available(
            when (request.section) {
                SyncRepairSection.LIVE -> operations.live(request)
                SyncRepairSection.EPG -> {
                    operations.epg(request)
                    SyncOutcome()
                }
                SyncRepairSection.MOVIES -> operations.movies(request)
                SyncRepairSection.SERIES -> operations.series(request)
            }
        )
    }

    override suspend fun syncGuide(
        request: ProviderGuideSyncRequest
    ): CapabilityResolution<ProviderGuideSyncResult> =
        CapabilityResolution.Available(operations.guide(request))
}

internal class JellyfinCatalogSyncPlan(
    private val operations: JellyfinCatalogSyncOperations
) : CatalogSyncPlan {
    override val providerType: ProviderType = ProviderType.JELLYFIN

    override suspend fun syncFull(request: FullProviderSyncRequest): SyncOutcome =
        operations.full(request)

    override suspend fun syncSection(
        request: SectionProviderSyncRequest
    ): CapabilityResolution<SyncOutcome> {
        return when (request.section) {
            SyncRepairSection.MOVIES -> {
                CapabilityResolution.Available(operations.movies(request))
            }
            SyncRepairSection.SERIES -> {
                CapabilityResolution.Available(operations.series(request))
            }
            SyncRepairSection.LIVE -> CapabilityResolution.Unsupported(
                "Live TV retry is unavailable for Jellyfin providers"
            )
            SyncRepairSection.EPG -> CapabilityResolution.Unsupported(
                "Native guide retry is unavailable for Jellyfin providers"
            )
        }
    }

    override suspend fun syncGuide(
        request: ProviderGuideSyncRequest
    ): CapabilityResolution<ProviderGuideSyncResult> =
        CapabilityResolution.Available(operations.guide(request))
}

internal object CatalogSyncPlanFactory {
    fun create(
        xtream: XtreamCatalogSyncOperations,
        m3u: M3uCatalogSyncOperations,
        stalker: StalkerCatalogSyncOperations,
        jellyfin: JellyfinCatalogSyncOperations
    ): CatalogSyncPlanRegistry = CatalogSyncPlanRegistry(
        listOf(
            XtreamCatalogSyncPlan(xtream),
            M3uCatalogSyncPlan(m3u),
            StalkerCatalogSyncPlan(stalker),
            JellyfinCatalogSyncPlan(jellyfin)
        )
    )
}
/** Builds provider plans from execution ports; provider full/live paths bypass the manager delegate. */
internal class CatalogSyncPlanAssembler(
    private val delegate: CatalogSyncPlanDelegate
) {
    fun create(): CatalogSyncPlanRegistry {
        return CatalogSyncPlanFactory.create(
            xtream = XtreamCatalogSyncOperations(
                full = delegate::syncXtreamFull,
                live = delegate::syncXtreamLive,
                epg = delegate::syncXtreamEpg,
                movies = delegate::syncXtreamMovies,
                series = delegate::syncXtreamSeries,
                guide = delegate::syncXtreamGuide
            ),
            m3u = M3uCatalogSyncOperations(
                full = delegate::syncM3uFull,
                live = delegate::syncM3uLive,
                epg = delegate::syncM3uEpg,
                movies = delegate::syncM3uMovies,
                guide = delegate::syncM3uGuide
            ),
            stalker = StalkerCatalogSyncOperations(
                full = delegate::syncStalkerFull,
                live = delegate::syncStalkerLive,
                epg = delegate::syncStalkerEpg,
                movies = delegate::syncStalkerMovies,
                series = delegate::syncStalkerSeries,
                guide = delegate::syncStalkerGuide
            ),
            jellyfin = JellyfinCatalogSyncOperations(
                full = delegate::syncJellyfinFull,
                movies = delegate::syncJellyfinMovies,
                series = delegate::syncJellyfinSeries,
                guide = delegate::syncJellyfinGuide
            )
        )
    }
}
