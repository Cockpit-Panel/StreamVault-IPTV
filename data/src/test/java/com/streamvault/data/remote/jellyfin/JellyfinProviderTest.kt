package com.streamvault.data.remote.jellyfin

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import org.junit.Test
import java.io.IOException

class JellyfinProviderTest {

    @Test
    fun `movie pages retain continuation metadata`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val start = request.url.queryParameter("StartIndex")
            val body = when (start) {
                "0" -> """{"TotalRecordCount":2,"Items":[{"Id":"movie-1","Name":"One"}]}"""
                "1" -> """{"TotalRecordCount":2,"Items":[{"Id":"movie-2","Name":"Two"}]}"""
                else -> error("Unexpected continuation: $start")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = JellyfinProvider(client, Gson())
        val account = Provider(name = "Jellyfin", type = ProviderType.JELLYFIN, serverUrl = "https://demo.example", username = "alice", password = "token")

        val first = provider.fetchMoviesPage(account, 0) as Result.Success
        assertThat(first.data.totalRecordCount).isEqualTo(2)
        assertThat(first.data.nextStartIndex).isEqualTo(1)
        val second = provider.fetchMoviesPage(account, first.data.nextStartIndex) as Result.Success
        assertThat(second.data.items.single().name).isEqualTo("Two")
    }

    @Test
    fun `movie page rejects server that ignores requested limit`() = runTest {
        val items = (1..101).joinToString(",") { "{\"Id\":\"movie-$it\"}" }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"TotalRecordCount\":101,\"Items\":[$items]}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = JellyfinProvider(client, Gson())
        val result = provider.fetchMoviesPage(Provider(name = "Jellyfin", type = ProviderType.JELLYFIN, serverUrl = "https://demo.example", username = "alice", password = "token"), 0)
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception).isInstanceOf(JellyfinPaginationException::class.java)
    }

    @Test
    fun `movie page rejects mismatched start index and early empty page`() = runTest {
        val bodies = ArrayDeque(
            listOf(
                """{"StartIndex":1,"TotalRecordCount":1,"Items":[{"Id":"movie-1"}]}""",
                """{"StartIndex":0,"TotalRecordCount":1,"Items":[]}"""
            )
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bodies.removeFirst().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = JellyfinProvider(client, Gson())
        val account = account()

        val mismatchedStart = provider.fetchMoviesPage(account, 0)
        val earlyEmpty = provider.fetchMoviesPage(account, 0)

        assertThat((mismatchedStart as Result.Error).exception).isInstanceOf(JellyfinPaginationException::class.java)
        assertThat((earlyEmpty as Result.Error).exception).isInstanceOf(JellyfinPaginationException::class.java)
    }

    @Test
    fun `movie page rejects oversized response before decoding`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ReportedLengthBody(4L * 1024L * 1024L + 1L, "{}".toByteArray()))
                .build()
        }.build()
        val result = JellyfinProvider(client, Gson()).fetchMoviesPage(account(), 0)

        assertThat((result as Result.Error).exception).isInstanceOf(JellyfinResponseTooLargeException::class.java)
    }

    @Test
    fun `movie page rejects huge single item field`() = runTest {
        val overview = "x".repeat(65_537)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{"StartIndex":0,"TotalRecordCount":1,"Items":[{"Id":"movie-1","Overview":"$overview"}]}"""
                        .toResponseBody("application/json".toMediaType())
                ).build()
        }.build()
        val result = JellyfinProvider(client, Gson()).fetchMoviesPage(account(), 0)

        assertThat((result as Result.Error).exception).isInstanceOf(JellyfinItemLimitException::class.java)
    }

    @Test
    fun `cancellation mid page cancels the in flight request`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.complete(Unit)
            while (!chain.call().isCanceled()) Thread.sleep(2)
            throw IOException("cancelled")
        }.build()
        val provider = JellyfinProvider(client, Gson())

        val request = async(Dispatchers.IO) { provider.fetchMoviesPage(account(), 0) }
        entered.await()
        request.cancelAndJoin()

        assertThat(request.isCancelled).isTrue()
    }

    @Test
    fun `episode pagination rejects repeated pages and changing totals`() = runTest {
        suspend fun runCase(secondTotal: Int, secondId: String): Result<List<com.streamvault.data.local.entity.EpisodeEntity>> {
            val responses = ArrayDeque(
                listOf(
                    """{"StartIndex":0,"TotalRecordCount":2,"Items":[{"Id":"ep-1","Name":"One"}]}""",
                    """{"StartIndex":1,"TotalRecordCount":$secondTotal,"Items":[{"Id":"$secondId","Name":"Two"}]}"""
                )
            )
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(responses.removeFirst().toResponseBody("application/json".toMediaType())).build()
            }.build()
            return JellyfinProvider(client, Gson()).fetchEpisodes(account(), "series-1", 99L)
        }

        val repeated = runCase(secondTotal = 2, secondId = "ep-1")
        val changed = runCase(secondTotal = 3, secondId = "ep-2")

        assertThat((repeated as Result.Error).exception).isInstanceOf(JellyfinPaginationException::class.java)
        assertThat((changed as Result.Error).exception).isInstanceOf(JellyfinPaginationException::class.java)
    }

    @Test
    fun `episode catalog ceiling is rejected from first page metadata`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{"StartIndex":0,"TotalRecordCount":10001,"Items":[{"Id":"ep-1","Name":"One"}]}"""
                        .toResponseBody("application/json".toMediaType())
                ).build()
        }.build()

        val result = JellyfinProvider(client, Gson()).fetchEpisodes(account(), "series-1", 99L)

        assertThat((result as Result.Error).exception).isInstanceOf(JellyfinCatalogLimitException::class.java)
    }

    @Test
    fun `fetchMovies does not embed access token in artwork urls`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertThat(request.url.queryParameter("StartIndex")).isEqualTo("0")
                assertThat(request.url.queryParameter("Limit")).isEqualTo("100")
                val body = when (request.url.encodedPath) {
                    "/Items" -> {
                        """
                        {
                          "TotalRecordCount": 1,
                          "Items": [
                            {
                              "Id": "movie-1",
                              "Name": "Movie 1",
                              "ImageTags": { "Primary": "poster-tag" },
                              "BackdropImageTags": ["backdrop-tag"]
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                    else -> error("Unexpected request path: ${request.url.encodedPath}")
                }

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = JellyfinProvider(
            okHttpClient = client,
            gson = Gson()
        )

        val result = provider.fetchMoviesPage(
            Provider(
                name = "Jellyfin",
                type = ProviderType.JELLYFIN,
                serverUrl = "https://demo.example",
                username = "alice",
                password = "secret-token"
            ),
            startIndex = 0
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val movie = (result as Result.Success).data.items.single()
        assertThat(movie.posterUrl).isEqualTo("https://demo.example/Items/movie-1/Images/Primary?tag=poster-tag&streamvault_provider_id=0")
        assertThat(movie.backdropUrl).isEqualTo("https://demo.example/Items/movie-1/Images/Backdrop/0?tag=backdrop-tag&streamvault_provider_id=0")
        assertThat(movie.posterUrl).doesNotContain("api_key")
        assertThat(movie.backdropUrl).doesNotContain("api_key")
    }

    private fun account() = Provider(
        name = "Jellyfin",
        type = ProviderType.JELLYFIN,
        serverUrl = "https://demo.example",
        username = "alice",
        password = "token"
    )

    private class ReportedLengthBody(
        private val reportedLength: Long,
        bytes: ByteArray
    ) : ResponseBody() {
        private val buffer = Buffer().write(bytes)
        override fun contentType(): MediaType = "application/json".toMediaType()
        override fun contentLength(): Long = reportedLength
        override fun source(): BufferedSource = buffer
    }
}
