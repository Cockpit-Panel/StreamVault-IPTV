package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class StalkerPortalReplayHarnessTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun replayFixtures_cover_supported_portal_families() = runTest {
        listOf(
            "stalker/fixtures/mac_basic.json",
            "stalker/fixtures/auth_required.json",
            "stalker/fixtures/auth_plus_mag.json",
            "stalker/fixtures/module_gated.json",
            "stalker/fixtures/nginx_temp_link.json",
            "stalker/fixtures/strict_mag_legacy.json",
            "stalker/fixtures/temp_link_strict.json",
            "stalker/fixtures/recipe_fallback.json",
            "stalker/fixtures/direct_cookie_playback.json",
            "stalker/fixtures/play_live_cookie.json",
            "stalker/fixtures/play_movie_vod.json",
            "stalker/fixtures/portal_endpoint_preferred.json",
            "stalker/fixtures/archive_create_link.json",
            "stalker/fixtures/archive_direct_cookie.json",
            "stalker/fixtures/archive_strict_mag.json",
            "stalker/fixtures/archive_recipe_fallback.json",
            "stalker/fixtures/on_demand_acceptance.json"
        ).forEach { path ->
            val fixture = loadFixture(path)
            val requestedActions = mutableListOf<String>()
            val service = OkHttpStalkerApiService(
                okHttpClient = fakeReplayClient(fixture, requestedActions),
                json = json
            )

            val profile = buildStalkerDeviceProfile(
                portalUrl = fixture.device.portalUrl,
                macAddress = fixture.device.macAddress,
                authMode = StalkerAuthMode.valueOf(fixture.device.authMode),
                endpointPreferenceHint = fixture.device.endpointPreference
                    ?.let(StalkerEndpointPreference::valueOf)
                    ?: StalkerEndpointPreference.AUTO,
                cookieModeHint = fixture.device.cookieMode
                    ?.let(StalkerCookieMode::valueOf)
                    ?: StalkerCookieMode.NONE,
                playbackBackendHint = fixture.device.playbackBackendHint
                    ?.let(StalkerPlaybackBackendHint::valueOf)
                    ?: StalkerPlaybackBackendHint.AUTO,
                username = fixture.device.username,
                password = fixture.device.password,
                deviceProfile = fixture.device.deviceProfile,
                timezone = fixture.device.timezone,
                locale = fixture.device.locale
            )
            val authResult = service.authenticate(profile)
            assertThat(authResult).isInstanceOf(Result.Success::class.java)
            val authSuccess = authResult as Result.Success
            assertWithFixture(path, authSuccess.data.first.effectiveAuthMode.name)
                .isEqualTo(fixture.expected.authMode)
            assertWithFixture(path, authSuccess.data.first.portalProfile.name)
                .isEqualTo(fixture.expected.portalProfile)
            assertWithMessage(path).that(authSuccess.data.first.bootstrapEvidence)
                .containsExactlyElementsIn(fixture.expected.bootstrapEvidence)
                .inOrder()
            fixture.expected.portalFingerprint?.let { expectedFingerprint ->
                assertWithFixture(path, authSuccess.data.first.portalFingerprint)
                    .isEqualTo(StalkerPortalFingerprint.valueOf(expectedFingerprint))
                assertWithFixture(path, authSuccess.data.second.portalFingerprint)
                    .isEqualTo(StalkerPortalFingerprint.valueOf(expectedFingerprint))
            }
            fixture.expected.magPreset?.let { expectedPreset ->
                assertWithFixture(path, authSuccess.data.first.magPreset)
                    .isEqualTo(StalkerMagPreset.valueOf(expectedPreset))
                assertWithFixture(path, authSuccess.data.second.magPreset)
                    .isEqualTo(StalkerMagPreset.valueOf(expectedPreset))
            }
            fixture.expected.bootstrapRecipe?.let { expectedRecipe ->
                assertWithFixture(path, authSuccess.data.first.bootstrapRecipe)
                    .isEqualTo(StalkerBootstrapRecipe.valueOf(expectedRecipe))
                assertWithFixture(path, authSuccess.data.second.bootstrapRecipe)
                    .isEqualTo(StalkerBootstrapRecipe.valueOf(expectedRecipe))
            }
            fixture.expected.recipeEvidence?.let { expectedEvidence ->
                assertThat(authSuccess.data.first.recipeEvidence)
                    .containsAtLeastElementsIn(expectedEvidence)
            }
            fixture.expected.endpointPreference?.let { expected ->
                assertWithFixture(path, authSuccess.data.first.fingerprintEvidence.endpointPreference)
                    .isEqualTo(StalkerEndpointPreference.valueOf(expected))
            }
            fixture.expected.cookieMode?.let { expected ->
                assertWithFixture(path, authSuccess.data.first.fingerprintEvidence.cookieMode)
                    .isEqualTo(StalkerCookieMode.valueOf(expected))
            }
            fixture.expected.playbackBackendHint?.let { expected ->
                assertWithFixture(path, authSuccess.data.first.fingerprintEvidence.playbackBackendHint)
                    .isEqualTo(StalkerPlaybackBackendHint.valueOf(expected))
            }
            fixture.expected.archiveEndpointPreference?.let { expected ->
                assertWithFixture(path, authSuccess.data.first.fingerprintEvidence.archiveEndpointPreference)
                    .isEqualTo(StalkerEndpointPreference.valueOf(expected))
            }
            fixture.expected.archiveViaCreateLink?.let { expected ->
                assertWithFixture(path, authSuccess.data.first.fingerprintEvidence.archiveViaCreateLink)
                    .isEqualTo(expected)
            }
            fixture.expected.archiveViaDirectUrl?.let { expected ->
                assertWithFixture(path, authSuccess.data.first.fingerprintEvidence.archiveViaDirectUrl)
                    .isEqualTo(expected)
            }
            fixture.expected.archiveRequiresBootstrapPrep?.let { expected ->
                assertWithFixture(path, authSuccess.data.first.fingerprintEvidence.archiveRequiresBootstrapPrep)
                    .isEqualTo(expected)
            }
            fixture.expected.archiveRequiresStrictCookies?.let { expected ->
                assertWithFixture(path, authSuccess.data.first.fingerprintEvidence.archiveRequiresStrictCookies)
                    .isEqualTo(expected)
            }

            fixture.expected.playbackMode?.let { expectedPlaybackMode ->
                val catalogType = fixture.expected.catalogType ?: "LIVE"
                val firstItem = when (catalogType.uppercase()) {
                    "VOD", "MOVIE" -> {
                        val vodResult = service.getVodStreams(
                            session = authSuccess.data.first,
                            profile = profile,
                            categoryId = null
                        )
                        assertThat(vodResult).isInstanceOf(Result.Success::class.java)
                        (vodResult as Result.Success).data.first()
                    }

                    else -> {
                        val liveResult = service.getLiveStreams(
                            session = authSuccess.data.first,
                            profile = profile,
                            categoryId = null
                        )
                        assertThat(liveResult).isInstanceOf(Result.Success::class.java)
                        (liveResult as Result.Success).data.first()
                    }
                }
                fixture.expected.resolvedPlaybackUrl?.let { expectedUrl ->
                    val createLinkResult = service.createLink(
                        session = authSuccess.data.first,
                        profile = profile,
                        kind = fixture.expected.streamKind
                            ?.let(StalkerStreamKind::valueOf)
                            ?: StalkerStreamKind.LIVE,
                        cmd = firstItem.cmd.orEmpty(),
                        seriesNumber = null,
                        archiveStartSeconds = fixture.expected.catchUpStartSeconds,
                        archiveEndSeconds = fixture.expected.catchUpEndSeconds
                    )
                    assertThat(createLinkResult).isInstanceOf(Result.Success::class.java)
                    val resolvedUrl = (createLinkResult as Result.Success).data
                    assertThat(resolvedUrl).isEqualTo(expectedUrl)
                    assertThat(detectStalkerPlaybackMode(resolvedUrl, firstItem.portalCapabilities).name)
                        .isEqualTo(expectedPlaybackMode)
                } ?: assertThat(firstItem.playbackDescriptor?.primaryMode?.name).isEqualTo(expectedPlaybackMode)
            }
            assertThat(requestedActions)
                .containsAtLeastElementsIn(fixture.expected.requestOrder)
                .inOrder()
        }
    }

    @Test
    fun onDemandAcceptanceFixture_bounds_setup_preview_paging_and_epg() = runTest {
        val path = "stalker/fixtures/on_demand_acceptance.json"
        val fixture = loadFixture(path)
        val requestedActions = mutableListOf<String>()
        val observed = mutableListOf<ReplayObservedRequest>()
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeReplayClient(fixture, requestedActions, observed, active, maximumActive),
            json = json
        )
        val profile = buildStalkerDeviceProfile(
            portalUrl = fixture.device.portalUrl,
            macAddress = fixture.device.macAddress,
            authMode = StalkerAuthMode.valueOf(fixture.device.authMode),
            deviceProfile = fixture.device.deviceProfile,
            timezone = fixture.device.timezone,
            locale = fixture.device.locale
        ).copy(providerId = 77L)

        val milestones = mutableListOf("AUTHENTICATING")
        val startedAt = System.currentTimeMillis()
        val auth = service.authenticate(profile) as Result.Success
        val authenticatedAt = System.currentTimeMillis()
        val session = auth.data.first

        assertThat(service.getLiveCategories(session, profile)).isInstanceOf(Result.Success::class.java)
        assertThat(service.streamLiveStreams(session, profile) {}).isInstanceOf(Result.Success::class.java)
        milestones += "LIVE_READY"
        assertThat(service.getVodCategories(session, profile)).isInstanceOf(Result.Success::class.java)
        assertThat(service.getSeriesCategories(session, profile)).isInstanceOf(Result.Success::class.java)
        milestones += "CATEGORIES_READY"
        val setupRequestCount = observed.size
        val readyAt = System.currentTimeMillis()
        milestones += "READY"

        fixture.operations.forEach { operation ->
            when (operation.type) {
                "MOVIE_PREVIEW" -> repeat(operation.pages.coerceIn(1, 2)) { offset ->
                    assertThat(service.getVodStreamsPage(session, profile, operation.categoryId, offset + 1))
                        .isInstanceOf(Result.Success::class.java)
                }
                "SERIES_PREVIEW" -> repeat(operation.pages.coerceIn(1, 2)) { offset ->
                    assertThat(service.getSeriesPage(session, profile, operation.categoryId, offset + 1))
                        .isInstanceOf(Result.Success::class.java)
                }
                "EPG" -> assertThat(service.streamBulkEpg(session, profile, periodHours = 6) {})
                    .isInstanceOf(Result.Success::class.java)
                "BACKGROUND_INDEX" -> assertThat(
                    service.getVodStreamsPage(session, profile, operation.categoryId, operation.page)
                ).isInstanceOf(Result.Success::class.java)
            }
        }

        val setupRequests = observed.take(setupRequestCount)
        fixture.expected.acceptanceRequestOrder?.let { expectedOrder ->
            assertThat(observed.map(ReplayObservedRequest::action))
                .containsExactlyElementsIn(expectedOrder)
                .inOrder()
        }
        fixture.expected.requestCount?.let { expectedCount ->
            assertThat(observed).hasSize(expectedCount)
        }
        fixture.expected.orderedPages?.let { expectedPages ->
            assertThat(observed.filter { it.action == "get_ordered_list" }.mapNotNull(ReplayObservedRequest::page))
                .containsExactlyElementsIn(expectedPages)
                .inOrder()
        }
        fixture.expected.readinessMilestones?.let { expectedMilestones ->
            assertThat(milestones).containsExactlyElementsIn(expectedMilestones).inOrder()
        }
        assertThat(authenticatedAt - startedAt).isLessThan(fixture.expected.maxAuthenticationMillis ?: 3_000L)
        assertThat(readyAt - startedAt).isLessThan(fixture.expected.maxReadinessMillis ?: 10_000L)
        assertThat(setupRequests.count { it.action == "get_ordered_list" }).isEqualTo(0)
        assertThat(maximumActive.get()).isAtMost(fixture.expected.maxConcurrency ?: 2)
        assertThat(observed.sumOf { it.responseBytes })
            .isAtLeast(fixture.expected.minResponseBytes ?: 1L)
        assertThat(observed.count { it.action == "get_ordered_list" && it.type == "vod" })
            .isAtMost((fixture.expected.maxMoviePagesAfterSetup ?: 3))
        assertThat(observed.count { it.action == "get_ordered_list" && it.type == "series" })
            .isAtMost((fixture.expected.maxSeriesPagesAfterSetup ?: 2))
        val epgIndex = observed.indexOfFirst { it.action == "get_epg_info" }
        val backgroundIndex = observed.indexOfLast { it.action == "get_ordered_list" && it.page == 3 }
        assertThat(epgIndex).isAtLeast(0)
        assertThat(backgroundIndex).isGreaterThan(epgIndex)
    }

    private fun <T> assertWithFixture(path: String, actual: T) =
        assertWithMessage(path).that(actual)

    private fun fakeReplayClient(
        fixture: ReplayFixture,
        requestedActions: MutableList<String>,
        observed: MutableList<ReplayObservedRequest>? = null,
        active: AtomicInteger? = null,
        maximumActive: AtomicInteger? = null
    ): OkHttpClient {
        val responsesByAction = fixture.responses.groupBy { "${it.method.uppercase()}:${it.action}" }
            .mapValues { (_, items) -> items.toMutableList() }
        val reusableBootstrapResponses = fixture.responses
            .filter { it.action in setOf("handshake", "do_auth") }
            .associateBy { "${it.method.uppercase()}:${it.action}" }
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val activeNow = active?.incrementAndGet() ?: 1
                maximumActive?.updateAndGet { previous -> maxOf(previous, activeNow) }
                val action = request.url.queryParameter("action").orEmpty()
                val method = request.method.uppercase()
                requestedActions += action
                val key = "$method:$action"
                val scripted = responsesByAction[key]?.removeFirstOrNull()
                    ?: reusableBootstrapResponses[key]
                    ?: error(
                        "Missing replay response for $key in fixture ${fixture.name}; " +
                            "path=${request.url.encodedPath}; " +
                            "requested=${requestedActions.joinToString(",")}"
                    )
                val builder = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(scripted.code)
                    .message("OK")
                    .body(scripted.body.toResponseBody("application/json".toMediaType()))
                scripted.headers.orEmpty().forEach { (name, value) ->
                    builder.addHeader(name, value)
                }
                observed?.add(
                    ReplayObservedRequest(
                        type = request.url.queryParameter("type").orEmpty(),
                        action = action,
                        page = request.url.queryParameter("p")?.toIntOrNull(),
                        responseBytes = scripted.body.toByteArray().size.toLong()
                    )
                )
                builder.build().also { active?.decrementAndGet() }
            }
            .build()
    }

    private fun loadFixture(path: String): ReplayFixture {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing replay fixture $path"
        }
        return stream.use { json.decodeFromString(ReplayFixture.serializer(), InputStreamReader(it).readText()) }
    }
}

@Serializable
private data class ReplayFixture(
    val name: String,
    val device: ReplayDevice,
    val responses: List<ReplayResponse>,
    val expected: ReplayExpectation,
    val operations: List<ReplayOperation> = emptyList()
)

@Serializable
private data class ReplayOperation(
    val type: String,
    val categoryId: String? = null,
    val page: Int = 1,
    val pages: Int = 1
)

private data class ReplayObservedRequest(
    val type: String,
    val action: String,
    val page: Int?,
    val responseBytes: Long
)

@Serializable
private data class ReplayDevice(
    val portalUrl: String,
    val macAddress: String,
    val authMode: String,
    val endpointPreference: String? = null,
    val cookieMode: String? = null,
    val playbackBackendHint: String? = null,
    val username: String = "",
    val password: String = "",
    val deviceProfile: String = "MAG250",
    val timezone: String = "UTC",
    val locale: String = "en"
)

@Serializable
private data class ReplayResponse(
    val action: String,
    val method: String = "GET",
    val code: Int = 200,
    val headers: Map<String, String>? = null,
    val body: String
)

@Serializable
private data class ReplayExpectation(
    val authMode: String,
    val portalProfile: String,
    val bootstrapEvidence: List<String>,
    val requestOrder: List<String>,
    val portalFingerprint: String? = null,
    val magPreset: String? = null,
    val bootstrapRecipe: String? = null,
    val recipeEvidence: List<String>? = null,
    val endpointPreference: String? = null,
    val cookieMode: String? = null,
    val playbackBackendHint: String? = null,
    val archiveEndpointPreference: String? = null,
    val archiveViaCreateLink: Boolean? = null,
    val archiveViaDirectUrl: Boolean? = null,
    val archiveRequiresBootstrapPrep: Boolean? = null,
    val archiveRequiresStrictCookies: Boolean? = null,
    val catalogType: String? = null,
    val streamKind: String? = null,
    val playbackMode: String? = null,
    val resolvedPlaybackUrl: String? = null,
    val catchUpStartSeconds: Long? = null,
    val catchUpEndSeconds: Long? = null,
    val maxAuthenticationMillis: Long? = null,
    val maxReadinessMillis: Long? = null,
    val maxConcurrency: Int? = null,
    val maxMoviePagesAfterSetup: Int? = null,
    val maxSeriesPagesAfterSetup: Int? = null,
    val acceptanceRequestOrder: List<String>? = null,
    val requestCount: Int? = null,
    val orderedPages: List<Int>? = null,
    val readinessMilestones: List<String>? = null,
    val minResponseBytes: Long? = null
)
