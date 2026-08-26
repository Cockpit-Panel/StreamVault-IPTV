package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class StalkerCatalogAcceptanceTest {
    @Test
    fun addConnection_rejectsEmptyLiveCatalog() = runTest {
        val service = service { action ->
            when (action) {
                "handshake" -> """{"js":{"token":"token"}}"""
                "get_profile" -> """{"js":{"id":"1","name":"Portal","status":"1","auth_access":true}}"""
                "get_genres" -> """{"js":[]}"""
                else -> """{"js":[]}"""
            }
        }

        val result = service.authenticate(profile(requireCatalog = true))

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("no live channel categories")
    }

    @Test
    fun addConnection_rejectsProfileWhenEveryCatalogWrapperIsMalformed() = runTest {
        val service = service { action ->
            when (action) {
                "handshake" -> """{"js":{"token":"token"}}"""
                "get_profile" -> """{"js":{"id":"1","name":"Portal","status":"1","auth_access":true}}"""
                else -> """{"js":{"unexpected":true}}"""
            }
        }

        val result = service.authenticate(profile(requireCatalog = true))

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception)
            .isInstanceOf(StalkerApiError.ReadinessInconclusive::class.java)
        assertThat((result.exception as StalkerApiError.ReadinessInconclusive).evidenceCode)
            .isEqualTo("LIVE_MALFORMED_RESPONSE")
    }

    private fun profile(requireCatalog: Boolean) = buildStalkerDeviceProfile(
        portalUrl = "https://portal.example.com/c",
        macAddress = "00:1A:79:12:34:56",
        deviceProfile = "MAG250",
        timezone = "UTC",
        locale = "en",
        requireCatalogValidation = requireCatalog
    )

    private fun service(bodyFor: (String) -> String): OkHttpStalkerApiService =
        OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyFor(request.url.queryParameter("action").orEmpty()).toResponseBody("application/json".toMediaType()))
                    .build()
            }.build(),
            json = Json { ignoreUnknownKeys = true }
        )
}
