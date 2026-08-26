package com.streamvault.data.remote.jellyfin

import com.google.gson.Gson
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.provider.ProviderConfigurationCodec
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.ProviderType
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class JellyfinImageAuthInterceptorTest {
    private val providerDao: ProviderDao = mock()
    private val providerSnapshotDao: ProviderSnapshotDao = mock()
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }
    private val configurationCodec = ProviderConfigurationCodec(Gson(), credentialCrypto)
    private val interceptor = JellyfinImageAuthInterceptor(
        providerDao,
        providerSnapshotDao,
        configurationCodec
    )

    @Test
    fun `two accounts on one server receive only their exact token`() {
        whenever(providerDao.getByIdSync(1L)).thenReturn(provider(1L, "account-a", "token-a"))
        whenever(providerDao.getByIdSync(2L)).thenReturn(provider(2L, "account-b", "token-b"))
        whenever(providerSnapshotDao.getConfigSync(1L)).thenReturn(config(1L, "account-a", "token-a"))
        whenever(providerSnapshotDao.getConfigSync(2L)).thenReturn(config(2L, "account-b", "token-b"))
        val captured = ConcurrentHashMap<Long, Request>()

        listOf(1L, 2L).parallelStream().forEach { providerId ->
            captured[providerId] = execute(
                "https://media.example/jellyfin/Items/item/Images/Primary" +
                    "?tag=art&streamvault_provider_id=$providerId"
            )
        }

        assertThat(captured.getValue(1L).header("Authorization")).isEqualTo(
            buildJellyfinAuthorizationHeader("https://media.example/jellyfin", "account-a", "token-a")
        )
        assertThat(captured.getValue(1L).header("Authorization")).doesNotContain("token-b")
        assertThat(captured.getValue(2L).header("Authorization")).isEqualTo(
            buildJellyfinAuthorizationHeader("https://media.example/jellyfin", "account-b", "token-b")
        )
        assertThat(captured.getValue(2L).header("Authorization")).doesNotContain("token-a")
        captured.values.forEach { request ->
            assertThat(request.url.queryParameter("streamvault_provider_id")).isNull()
        }
    }

    @Test
    fun `provider edits and deletes take effect on the next request`() {
        whenever(providerDao.getByIdSync(7L))
            .thenReturn(provider(7L, "account", "old-token"))
            .thenReturn(provider(7L, "account", "new-token"))
            .thenReturn(null)
        whenever(providerSnapshotDao.getConfigSync(7L))
            .thenReturn(config(7L, "account", "old-token"))
            .thenReturn(config(7L, "account", "new-token"))
        val url = "https://media.example/jellyfin/Items/item/Images/Primary?streamvault_provider_id=7"

        val beforeEdit = execute(url)
        val afterEdit = execute(url)
        val afterDelete = execute(url)

        assertThat(beforeEdit.header("Authorization")).contains("Token=\"old-token\"")
        assertThat(afterEdit.header("Authorization")).contains("Token=\"new-token\"")
        assertThat(afterDelete.header("Authorization")).isNull()
        assertThat(afterDelete.url.queryParameter("streamvault_provider_id")).isNull()
    }

    @Test
    fun `same host with a different base path is not authenticated`() {
        whenever(providerDao.getByIdSync(3L)).thenReturn(
            provider(3L, "account", "secret", serverUrl = "https://media.example/jellyfin")
        )
        whenever(providerSnapshotDao.getConfigSync(3L)).thenReturn(
            config(3L, "account", "secret", serverUrl = "https://media.example/jellyfin")
        )

        val captured = execute(
            "https://media.example/other/Items/item/Images/Primary?streamvault_provider_id=3"
        )

        assertThat(captured.header("Authorization")).isNull()
        assertThat(captured.url.queryParameter("streamvault_provider_id")).isNull()
    }

    @Test
    fun `internal marker is stripped when caller already supplied authorization`() {
        val captured = execute(
            url = "https://media.example/jellyfin/Items/item/Images/Primary?streamvault_provider_id=9",
            authorization = "Bearer caller-token"
        )

        assertThat(captured.header("Authorization")).isEqualTo("Bearer caller-token")
        assertThat(captured.url.queryParameter("streamvault_provider_id")).isNull()
    }

    private fun execute(url: String, authorization: String? = null): Request {
        lateinit var captured: Request
        val terminal = Interceptor { chain ->
            captured = chain.request()
            Response.Builder()
                .request(captured)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("ok".toResponseBody("text/plain".toMediaType()))
                .build()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor(terminal)
            .build()
        val request = Request.Builder()
            .url(url)
            .apply { authorization?.let { header("Authorization", it) } }
            .build()
        client.newCall(request).execute().close()
        return captured
    }

    private fun provider(
        id: Long,
        username: String,
        token: String,
        serverUrl: String = "https://media.example/jellyfin"
    ) = ProviderEntity(
        id = id,
        name = "Jellyfin $id",
        type = ProviderType.JELLYFIN
    )

    private fun config(
        id: Long,
        username: String,
        token: String,
        serverUrl: String = "https://media.example/jellyfin"
    ): ProviderConfigEntity {
        val configuration = JellyfinConfig(serverUrl, username, token)
        return ProviderConfigEntity(
            providerId = id,
            type = ProviderType.JELLYFIN,
            schemaVersion = configuration.schemaVersion,
            configurationGeneration = 1L,
            identityKey = configurationCodec.identityKey(configuration),
            encryptedConfigJson = configurationCodec.encode(configuration),
            updatedAt = 1L
        )
    }
}
