package com.streamvault.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.epg.EpgResolutionEngine
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ChannelEpgMappingDao
import com.streamvault.data.local.dao.EpgChannelDao
import com.streamvault.data.local.dao.EpgProgrammeDao
import com.streamvault.data.local.dao.EpgSourceDao
import com.streamvault.data.local.dao.ProviderEpgSourceDao
import com.streamvault.data.local.entity.ChannelEpgMappingEntity
import com.streamvault.data.local.entity.EpgChannelEntity
import com.streamvault.data.local.entity.EpgProgrammeEntity
import com.streamvault.data.local.entity.EpgSourceEntity
import com.streamvault.data.local.entity.ProviderEpgSourceEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.data.parser.XmltvIngestionLimits
import com.streamvault.data.parser.XmltvLimitExceeded
import com.streamvault.data.parser.XmltvLimitKind
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgSourceType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.XmltvTimezonePolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.zip.GZIPOutputStream

class EpgSourceRepositoryImplTest {

    @Test
    fun `limitEpgInput rejects bytes after the configured decompressed limit`() {
        val input = limitEpgInput(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 3)

        assertThat(input.read(ByteArray(3))).isEqualTo(3)
        assertThrows(IOException::class.java) { input.read() }
    }

    @Test
    fun `limitEpgInput accepts input exactly at the configured limit`() {
        val input = limitEpgInput(ByteArrayInputStream(byteArrayOf(1, 2, 3)), maxBytes = 3)

        assertThat(input.readBytes()).hasLength(3)
    }

    @Test
    fun `small gzip is rejected when expansion crosses decompressed boundary`() {
        val expanded = "<tv><desc>${"x".repeat(512)}</desc></tv>".toByteArray()
        val compressed = gzip(expanded)
        val limits = XmltvIngestionLimits(
            maxRawBytes = compressed.size.toLong(),
            maxDecompressedBytes = 128
        )

        val error = assertThrows(XmltvLimitExceeded::class.java) {
            openLimitedXmltvInput(
                ByteArrayInputStream(compressed),
                "https://example.com/guide.gz",
                XmltvParser(),
                limits
            ).use(InputStream::readBytes)
        }

        assertThat(error.kind).isEqualTo(XmltvLimitKind.DECOMPRESSED_BYTES)
    }

    @Test
    fun `chunked gzip cannot bypass decompressed boundary`() {
        val expanded = "<tv><desc>${"y".repeat(512)}</desc></tv>".toByteArray()
        val compressed = gzip(expanded)
        val chunked = object : FilterInputStream(ByteArrayInputStream(compressed)) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int =
                super.read(bytes, off, len.coerceAtMost(3))
        }

        val error = assertThrows(XmltvLimitExceeded::class.java) {
            openLimitedXmltvInput(
                chunked,
                "https://example.com/chunked",
                XmltvParser(),
                XmltvIngestionLimits(
                    maxRawBytes = compressed.size.toLong(),
                    maxDecompressedBytes = 128
                )
            ).use(InputStream::readBytes)
        }

        assertThat(error.kind).isEqualTo(XmltvLimitKind.DECOMPRESSED_BYTES)
    }

    @Test
    fun `compressed transport is rejected at the smaller raw boundary`() {
        val compressed = gzip("<tv>${"z".repeat(512)}</tv>".toByteArray())

        val error = assertThrows(XmltvLimitExceeded::class.java) {
            openLimitedXmltvInput(
                ByteArrayInputStream(compressed),
                "https://example.com/guide.gz",
                XmltvParser(),
                XmltvIngestionLimits(maxRawBytes = compressed.size.toLong() - 1, maxDecompressedBytes = 10_000)
            ).use(InputStream::readBytes)
        }

        assertThat(error.kind).isEqualTo(XmltvLimitKind.RAW_BYTES)
    }

    private val context: Context = mock()
    private val contentResolver: ContentResolver = mock()
    private val epgSourceDao: EpgSourceDao = mock()
    private val providerEpgSourceDao: ProviderEpgSourceDao = mock()
    private val channelEpgMappingDao: ChannelEpgMappingDao = mock()
    private val epgChannelDao: EpgChannelDao = mock()
    private val epgProgrammeDao: EpgProgrammeDao = mock()
    private val xmltvParser: XmltvParser = mock()
    private val okHttpClient: OkHttpClient = mock()
    private val epgHttpClientBuilder: OkHttpClient.Builder = mock()
    private val resolutionEngine: EpgResolutionEngine = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private lateinit var repository: EpgSourceRepositoryImpl

    @Before
    fun setup() {
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(preferencesRepository.getHiddenCategoryIds(any(), eq(ContentType.LIVE))).thenReturn(flowOf(emptySet()))
        whenever(okHttpClient.newBuilder()).thenReturn(epgHttpClientBuilder)
        whenever(epgHttpClientBuilder.readTimeout(any<Long>(), any())).thenReturn(epgHttpClientBuilder)
        whenever(epgHttpClientBuilder.build()).thenReturn(okHttpClient)
        runBlocking {
        }
        repository = EpgSourceRepositoryImpl(
            context = context,
            epgSourceDao = epgSourceDao,
            providerEpgSourceDao = providerEpgSourceDao,
            channelEpgMappingDao = channelEpgMappingDao,
            epgChannelDao = epgChannelDao,
            epgProgrammeDao = epgProgrammeDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClient,
            resolutionEngine = resolutionEngine,
            preferencesRepository = preferencesRepository,
            transactionRunner = transactionRunner
        )
    }

    @Test
    fun `assignSourceToProvider_resolvesProviderImmediately`() = runTest {
        whenever(epgSourceDao.getById(10L)).thenReturn(
            EpgSourceEntity(id = 10L, name = "Primary", url = "https://example.com/epg.xml")
        )

        val result = repository.assignSourceToProvider(providerId = 7L, epgSourceId = 10L, priority = 1)

        assertThat(result is Result.Success).isTrue()
        verify(providerEpgSourceDao).insert(any())
        verify(resolutionEngine).resolveForProvider(7L, emptySet())
    }

    @Test
    fun `setSourceEnabled_resolvesEachAffectedProviderOnce`() = runTest {
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(7L, 8L, 7L))
        whenever(preferencesRepository.getHiddenCategoryIds(7L, ContentType.LIVE)).thenReturn(flowOf(setOf(101L)))
        whenever(preferencesRepository.getHiddenCategoryIds(8L, ContentType.LIVE)).thenReturn(flowOf(setOf(202L)))

        repository.setSourceEnabled(10L, enabled = false)

        verify(epgSourceDao).setEnabled(eq(10L), eq(false), any())
        verify(resolutionEngine).resolveForProvider(7L, setOf(101L))
        verify(resolutionEngine).resolveForProvider(8L, setOf(202L))
        verifyNoMoreInteractions(resolutionEngine)
    }

    @Test
    fun `deleteSource_rebuildsAffectedProviderMappings`() = runTest {
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(4L, 5L))
        whenever(preferencesRepository.getHiddenCategoryIds(4L, ContentType.LIVE)).thenReturn(flowOf(setOf(401L)))
        whenever(preferencesRepository.getHiddenCategoryIds(5L, ContentType.LIVE)).thenReturn(flowOf(setOf(501L)))

        repository.deleteSource(10L)

        verify(epgProgrammeDao).deleteBySource(10L)
        verify(epgChannelDao).deleteBySource(10L)
        verify(epgSourceDao).delete(10L)
        verify(resolutionEngine).resolveForProvider(4L, setOf(401L))
        verify(resolutionEngine).resolveForProvider(5L, setOf(501L))
    }

    @Test
    fun `applyManualOverride_persistsManualExternalMapping`() = runTest {
        whenever(providerEpgSourceDao.getEnabledForProviderSync(7L)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1L, providerId = 7L, epgSourceId = 10L, priority = 0))
        )
        whenever(epgChannelDao.getBySourceAndChannelId(10L, "bbc.one")).thenReturn(
            EpgChannelEntity(
                id = 5L,
                epgSourceId = 10L,
                xmltvChannelId = "bbc.one",
                displayName = "BBC One"
            )
        )
        whenever(channelEpgMappingDao.getForChannel(7L, 101L)).thenReturn(null)

        val result = repository.applyManualOverride(
            providerId = 7L,
            channelId = 101L,
            epgSourceId = 10L,
            xmltvChannelId = "bbc.one"
        )

        assertThat(result is Result.Success).isTrue()
        val captor = argumentCaptor<ChannelEpgMappingEntity>()
        verify(channelEpgMappingDao).upsert(captor.capture())
        assertThat(captor.firstValue.providerId).isEqualTo(7L)
        assertThat(captor.firstValue.providerChannelId).isEqualTo(101L)
        assertThat(captor.firstValue.epgSourceId).isEqualTo(10L)
        assertThat(captor.firstValue.xmltvChannelId).isEqualTo("bbc.one")
        assertThat(captor.firstValue.sourceType).isEqualTo(EpgSourceType.EXTERNAL.name)
        assertThat(captor.firstValue.matchType).isEqualTo(EpgMatchType.MANUAL.name)
        assertThat(captor.firstValue.isManualOverride).isTrue()
    }

    @Test
    fun `clearManualOverride_rebuildsAutomaticResolution`() = runTest {
        whenever(channelEpgMappingDao.getForChannel(7L, 101L)).thenReturn(
            ChannelEpgMappingEntity(
                id = 1L,
                providerChannelId = 101L,
                providerId = 7L,
                sourceType = EpgSourceType.EXTERNAL.name,
                epgSourceId = 10L,
                xmltvChannelId = "bbc.one",
                matchType = EpgMatchType.MANUAL.name,
                confidence = 1f,
                isManualOverride = true
            )
        )

        val result = repository.clearManualOverride(providerId = 7L, channelId = 101L)

        assertThat(result is Result.Success).isTrue()
        verify(resolutionEngine).resolveForProvider(7L, emptySet())
    }

    @Test
    fun `getOverrideCandidates_readsEnabledAssignedSourcesOnly`() = runTest {
        whenever(providerEpgSourceDao.getEnabledForProviderSync(7L)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1L, providerId = 7L, epgSourceId = 10L, priority = 0))
        )
        whenever(epgSourceDao.getAllSync()).thenReturn(
            listOf(EpgSourceEntity(id = 10L, name = "Primary", url = "https://example.com/epg.xml"))
        )
        whenever(epgChannelDao.searchBySource(10L, "%bbc%", 150)).thenReturn(
            listOf(
                EpgChannelEntity(id = 1L, epgSourceId = 10L, xmltvChannelId = "bbc.one", displayName = "BBC One")
            )
        )

        val result = repository.getOverrideCandidates(providerId = 7L, query = "bbc")

        assertThat(result.map { it.displayName }).containsExactly("BBC One")
        assertThat(result.single().epgSourceName).isEqualTo("Primary")
    }

    @Test
    fun `refreshSource_closesDecompressedXmlStream`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "content://epg/source.xml.gz"
        )
        val decompressedStream = CloseTrackingInputStream()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(contentResolver.openInputStream(Uri.parse(source.url))).thenReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenReturn(decompressedStream)
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(emptyList())

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        assertThat(decompressedStream.closed).isTrue()
    }

    @Test
    fun `refreshSource returns typed error cleans staging and preserves active rows on overflow`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "content://epg/source.xml"
        )

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(contentResolver.openInputStream(Uri.parse(source.url))).thenReturn(ByteArrayInputStream("<tv/>".toByteArray()))
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenAnswer { it.arguments[1] }
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(emptyList())
        doAnswer { throw XmltvLimitExceeded(XmltvLimitKind.PROGRAMMES, 1_000_000) }
            .whenever(xmltvParser)
            .parseStreamingWithChannels(any(), anyOrNull(), any(), any(), any())

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Error).isTrue()
        assertThat((result as Result.Error).exception).isInstanceOf(XmltvLimitExceeded::class.java)
        assertThat(result.message).isEqualTo("EPG programmes exceeded safety limit")
        verify(epgProgrammeDao, times(2)).deleteBySource(-10L)
        verify(epgChannelDao, times(2)).deleteBySource(-10L)
        verify(epgSourceDao, times(2)).delete(-10L)
        verify(epgProgrammeDao, never()).deleteBySource(10L)
        verify(epgChannelDao, never()).deleteBySource(10L)
        verify(epgProgrammeDao, never()).moveToSource(any(), any())
        verify(epgChannelDao, never()).moveToSource(any(), any())
        verify(epgSourceDao).updateRefreshError(eq(10L), eq("EPG programmes exceeded safety limit"), any())
    }

    @Test
    fun `refreshSource imports gzip xmltv when download url has no gz suffix`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "MyEPG",
            url = "https://myepg.example/download?order=private&key=redacted",
            lastRefreshAt = 0L
        )
        val response = Response.Builder()
            .request(Request.Builder().url(source.url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                gzip(
                    """
                    <tv>
                      <channel id="myepg.ch1">
                        <display-name>MyEPG Channel</display-name>
                      </channel>
                      <programme channel="myepg.ch1" start="20260101000000 +0000" stop="20260101010000 +0000">
                        <title>MyEPG News</title>
                      </programme>
                    </tv>
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                ).toResponseBody("application/octet-stream".toMediaType())
            )
            .build()
        val call: Call = mock()
        val repositoryWithRealParser = EpgSourceRepositoryImpl(
            context = context,
            epgSourceDao = epgSourceDao,
            providerEpgSourceDao = providerEpgSourceDao,
            channelEpgMappingDao = channelEpgMappingDao,
            epgChannelDao = epgChannelDao,
            epgProgrammeDao = epgProgrammeDao,
            xmltvParser = XmltvParser(),
            okHttpClient = okHttpClient,
            resolutionEngine = resolutionEngine,
            preferencesRepository = preferencesRepository,
            transactionRunner = transactionRunner
        )

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        enqueueResponse(call, response)
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(emptyList())

        val result = repositoryWithRealParser.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        val channelCaptor = argumentCaptor<List<EpgChannelEntity>>()
        val programmeCaptor = argumentCaptor<List<EpgProgrammeEntity>>()
        verify(epgChannelDao).insertAll(channelCaptor.capture())
        verify(epgProgrammeDao).insertAll(programmeCaptor.capture())
        assertThat(channelCaptor.firstValue.single().xmltvChannelId).isEqualTo("myepg.ch1")
        assertThat(channelCaptor.firstValue.single().displayName).isEqualTo("MyEPG Channel")
        assertThat(channelCaptor.firstValue.single().epgSourceId).isEqualTo(-10L)
        assertThat(programmeCaptor.firstValue.single().title).isEqualTo("MyEPG News")
        assertThat(programmeCaptor.firstValue.single().epgSourceId).isEqualTo(-10L)
        verify(epgSourceDao).insert(argThat {
            id == -10L &&
                url == "streamvault://epg-source-staging/10" &&
                !enabled
        })
        inOrder(epgProgrammeDao, epgChannelDao, epgSourceDao).apply {
            verify(epgProgrammeDao).deleteBySource(-10L)
            verify(epgChannelDao).deleteBySource(-10L)
            verify(epgSourceDao).delete(-10L)
            verify(epgSourceDao).insert(argThat {
                id == -10L &&
                    url == "streamvault://epg-source-staging/10" &&
                    !enabled
            })
            verify(epgChannelDao).insertAll(any())
            verify(epgProgrammeDao).insertAll(any())
        }
        verify(epgChannelDao).moveToSource(-10L, 10L)
        verify(epgProgrammeDao).moveToSource(-10L, 10L)
        verify(epgSourceDao, times(2)).delete(-10L)
        verify(epgSourceDao).updateRefreshSuccess(eq(10L), any())
    }

    @Test
    fun `refreshSource requests identity encoding so raw bytes are bounded before decompression`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "MyEPG",
            url = "https://myepg.example/download?order=private&key=redacted",
            lastRefreshAt = 0L
        )
        val requestCaptor = argumentCaptor<Request>()
        val response = Response.Builder()
            .request(Request.Builder().url(source.url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("<tv></tv>".toResponseBody("application/xml".toMediaType()))
            .build()
        val call: Call = mock()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(requestCaptor.capture())).thenReturn(call)
        enqueueResponse(call, response)
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(emptyList())
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenAnswer { it.arguments[1] }

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        assertThat(requestCaptor.firstValue.header("Accept-Encoding")).isEqualTo("identity")
    }

    @Test
    fun `refreshSource rebuilds affected providers when remote source returns 304`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "https://example.com/epg.xml",
            lastRefreshAt = 0L,
            etag = "etag-1"
        )
        val request = Request.Builder().url(source.url).build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(304)
            .message("Not Modified")
            .body("".toResponseBody())
            .build()
        val call: Call = mock()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        enqueueResponse(call, response)
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(7L, 8L))

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        verify(epgSourceDao).updateRefreshSuccess(eq(10L), any())
        verify(resolutionEngine).resolveForProvider(7L, emptySet())
        verify(resolutionEngine).resolveForProvider(8L, emptySet())
    }

    @Test
    fun `refreshSource uses explicit source timezone regardless of assignments`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "https://example.com/epg.xml",
            timezonePolicy = XmltvTimezonePolicy.EXPLICIT_ZONE,
            timezoneId = "America/New_York"
        )
        val request = Request.Builder().url(source.url).build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("<tv></tv>".toResponseBody())
            .build()
        val call: Call = mock()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        enqueueResponse(call, response)
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenAnswer { it.arguments[1] }
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(7L, 8L))

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        verify(xmltvParser).parseStreamingWithChannels(any(), eq("America/New_York"), any(), any(), any())
    }

    @Test
    fun `refreshSource requires offsets when source has no timezone override`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "https://example.com/epg.xml"
        )
        val request = Request.Builder().url(source.url).build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("<tv></tv>".toResponseBody())
            .build()
        val call: Call = mock()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        enqueueResponse(call, response)
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenAnswer { it.arguments[1] }
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(7L, 8L))

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        verify(xmltvParser).parseStreamingWithChannels(any(), isNull(), any(), any(), any())
    }

    @Test
    fun `timezone policy validation accepts only valid explicit IANA zones`() {
        val valid = validateXmltvTimezonePolicy(
            XmltvTimezonePolicy.EXPLICIT_ZONE,
            " Europe/Amsterdam "
        )
        val invalid = validateXmltvTimezonePolicy(
            XmltvTimezonePolicy.EXPLICIT_ZONE,
            "Mars/Olympus"
        )
        val missing = validateXmltvTimezonePolicy(
            XmltvTimezonePolicy.EXPLICIT_ZONE,
            null
        )

        assertThat((valid as Result.Success).data).isEqualTo("Europe/Amsterdam")
        assertThat(invalid).isInstanceOf(Result.Error::class.java)
        assertThat(missing).isInstanceOf(Result.Error::class.java)
    }

    private class CloseTrackingInputStream : ByteArrayInputStream(byteArrayOf()) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun enqueueResponse(call: Call, response: Response) {
        doAnswer { invocation ->
            (invocation.arguments[0] as Callback).onResponse(call, response)
            null
        }.whenever(call).enqueue(any())
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
