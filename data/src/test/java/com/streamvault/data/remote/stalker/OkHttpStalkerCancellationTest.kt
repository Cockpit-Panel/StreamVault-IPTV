package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.DiscoveryBudget
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.measureTimeMillis
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Test

class OkHttpStalkerCancellationTest {

    @Test
    fun `cancelling handshake prevents later recipe requests`(): Unit = runBlocking {
        val started = CountDownLatch(1)
        val actions = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            actions += chain.request().url.queryParameter("action").orEmpty()
            started.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw IOException("cancelled")
        }.build()
        val service = service(client)
        val job = launch(Dispatchers.IO) {
            service.authenticate(profile(DiscoveryBudget(10_000, 24)))
        }

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        job.cancelAndJoin()

        assertThat(actions).containsExactly("handshake")
    }

    @Test
    fun `request budget prevents an additional outbound request`(): Unit = runBlocking {
        val actions = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val action = chain.request().url.queryParameter("action").orEmpty()
            actions += action
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"js":{"token":"token-123"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val result = service(client).authenticate(profile(DiscoveryBudget(10_000, 1)))

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(actions).containsExactly("handshake")
    }

    @Test
    fun `overall discovery deadline cancels the active request`(): Unit = runBlocking {
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            while (!chain.call().isCanceled()) Thread.sleep(5)
            cancelled.countDown()
            throw IOException("cancelled")
        }.build()
        lateinit var result: Result<Pair<StalkerSession, StalkerProviderProfile>>

        val elapsedMillis = measureTimeMillis {
            result = service(client).authenticate(profile(DiscoveryBudget(100, 24)))
        }

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception)
            .isInstanceOf(StalkerApiError.DiscoveryBudgetExceeded::class.java)
        assertThat(error.message).contains("budget")
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(elapsedMillis).isLessThan(5_000L)
    }

    @Test
    fun `cancelling every discovery stage prevents later fallback requests`() = runBlocking {
        data class Scenario(
            val name: String,
            val targetAction: String,
            val profile: StalkerDeviceProfile
        )

        val scenarios = listOf(
            Scenario("profile", "get_profile", profile()),
            Scenario(
                "credentials",
                "do_auth",
                profile(
                    authMode = StalkerAuthMode.MAC_PLUS_CREDENTIALS,
                    username = "user",
                    password = "password"
                )
            ),
            Scenario(
                "modules",
                "get_modules",
                profile(portalFingerprint = StalkerPortalFingerprint.MODULE_GATED)
            ),
            Scenario(
                "catalog",
                "get_ordered_list",
                profile(requireCatalogValidation = true)
            )
        )

        scenarios.forEach { scenario ->
            val actions = CopyOnWriteArrayList<String>()
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val action = chain.request().url.queryParameter("action").orEmpty()
                actions += action
                if (action == scenario.targetAction) {
                    started.countDown()
                    while (!chain.call().isCanceled()) Thread.sleep(5)
                    cancelled.countDown()
                    throw IOException("cancelled")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(discoveryResponse(action).toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

            val job = launch(Dispatchers.IO) {
                OkHttpStalkerApiService(client, Json { ignoreUnknownKeys = true })
                    .authenticate(scenario.profile)
            }

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
            job.cancelAndJoin()

            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(actions.last()).isEqualTo(scenario.targetAction)
        }
    }

    @Test
    fun `cancelling a streamed catalog closes the response body`() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val bodyClosed = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(blockingBody(chain, started, cancelled, bodyClosed))
                .build()
        }.build()
        val service = service(client)
        val profile = profile()
        val session = StalkerSession(
            loadUrl = "https://portal.example.com/server/load.php",
            portalReferer = "https://portal.example.com/c/",
            token = "token-123"
        )
        var emittedItems = 0

        val job = launch(Dispatchers.IO) {
            service.streamLiveStreams(session, profile) { emittedItems++ }
        }

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        job.cancelAndJoin()

        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(bodyClosed.get()).isTrue()
        assertThat(emittedItems).isEqualTo(0)
    }

    @Test
    fun `cancelling after streamed headers does not learn response cookies`() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val requestCookies = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val action = chain.request().url.queryParameter("action").orEmpty()
            requestCookies += chain.request().header("Cookie").orEmpty()
            if (action == "get_all_channels") {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Set-Cookie", "learned=cancelled; Max-Age=3600; Path=/")
                    .body(blockingBody(chain, started, cancelled, AtomicBoolean(false)))
                    .build()
            } else {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"js":[]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }.build()
        val service = service(client)
        val profile = profile()
        val session = StalkerSession(
            loadUrl = "https://portal.example.com/server/load.php",
            portalReferer = "https://portal.example.com/c/",
            token = "token-123"
        )

        val job = launch(Dispatchers.IO) {
            service.streamLiveStreams(session, profile) { }
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        job.cancelAndJoin()
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue()

        service.getLiveCategories(session, profile)

        assertThat(requestCookies).hasSize(2)
        assertThat(requestCookies.last()).doesNotContain("learned=cancelled")
    }

    private fun service(client: OkHttpClient) =
        OkHttpStalkerApiService(client, Json { ignoreUnknownKeys = true })

    private fun profile(
        budget: DiscoveryBudget = DiscoveryBudget(),
        authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
        portalFingerprint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
        username: String = "",
        password: String = "",
        requireCatalogValidation: Boolean = false
    ) = buildStalkerDeviceProfile(
        portalUrl = "https://portal.example.com/c",
        macAddress = "00:1A:79:12:34:56",
        authMode = authMode,
        portalFingerprintHint = portalFingerprint,
        username = username,
        password = password,
        deviceProfile = "MAG250",
        timezone = "UTC",
        locale = "en",
        requireCatalogValidation = requireCatalogValidation,
        discoveryBudget = budget
    )

    private fun discoveryResponse(action: String): String = when (action) {
        "handshake" -> """{"js":{"token":"token-123","random":"random-123"}}"""
        "do_auth" -> """{"js":{"auth":1}}"""
        "get_profile", "get_main_info" ->
            """{"js":{"id":"42","name":"Test Portal","status":"1","auth_access":true}}"""
        "get_modules" -> """{"js":{"modules":{"itv":1,"vod":1}}}"""
        "get_localization" -> """{"js":{"lang":"en","timezone":"UTC"}}"""
        "get_events" -> """{"js":[]}"""
        "get_genres" -> """{"js":[{"id":"1","title":"News","name":"News"}]}"""
        "get_ordered_list" ->
            """{"js":{"data":[{"id":"1","name":"Channel","cmd":"http://stream.example/channel"}]}}"""
        else -> """{"js":{}}"""
    }

    private fun blockingBody(
        chain: okhttp3.Interceptor.Chain,
        started: CountDownLatch,
        cancelled: CountDownLatch,
        closed: AtomicBoolean
    ): ResponseBody = object : ResponseBody() {
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                started.countDown()
                while (!chain.call().isCanceled()) Thread.sleep(5)
                cancelled.countDown()
                throw IOException("cancelled")
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() {
                closed.set(true)
            }
        }.buffer()
    }
}
