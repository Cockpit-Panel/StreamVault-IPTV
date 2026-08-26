package com.streamvault.data.parser

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/**
 * Unit tests for [XmltvParser].
 *
 * All tests operate on in-memory XML strings.
 *
 * On the JVM test runner there is no Android XmlPullParser implementation registered
 * by default. We register kxml2 (a JVM-compatible XmlPull implementation) in setUp()
 * via the standard XmlPullParserFactory system property.
 *
 * Time reference: 2025-01-01 12:00:00 UTC = 1_735_732_800_000 ms epoch
 *                 2025-01-01 09:00:00 UTC = 1_735_722_000_000 ms epoch
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XmltvParserTest {

    private lateinit var parser: XmltvParser

    @Before
    fun setUp() {
        // Register kxml2 so XmlPullParserFactory.newInstance() works on the JVM.
        System.setProperty(
            "org.xmlpull.v1.XmlPullParserFactory",
            "org.kxml2.io.KXmlParser"
        )
        parser = XmltvParser()
    }

    // ── Happy path ────────────────────────────────────────────────

    @Test
    fun `parse_validXmltv_returnsPrograms`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Morning News</title>
                <desc>Today's news</desc>
              </programme>
              <programme start="20250101130000 +0000" stop="20250101140000 +0000" channel="ch1">
                <title>Tech Hour</title>
              </programme>
              <programme start="20250101140000 +0000" stop="20250101150000 +0000" channel="ch2">
                <title>Weather Report</title>
                <desc>Forecast</desc>
              </programme>
              <programme start="20250101150000 +0000" stop="20250101160000 +0000" channel="ch2">
                <title>Top Stories</title>
              </programme>
              <programme start="20250101160000 +0000" stop="20250101170000 +0000" channel="ch3">
                <title>Documentary</title>
                <desc>Natural World</desc>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream())

        assertThat(programs).hasSize(5)

        val first = programs[0]
        assertThat(first.channelId).isEqualTo("ch1")
        assertThat(first.title).isEqualTo("Morning News")
        assertThat(first.description).isEqualTo("Today's news")
        assertThat(first.startTime).isGreaterThan(0L)
        assertThat(first.endTime).isGreaterThan(first.startTime)
    }

    @Test
    fun `parse_multipleDateFormats_allParsed`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Format One</title>
              </programme>
              <programme start="20250101140000" stop="20250101150000" channel="ch1">
                <title>Format Two</title>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream(), timezoneId = "UTC")

        assertThat(programs).hasSize(2)
        // Both programs should have non-zero timestamps
        programs.forEach { prog ->
            assertThat(prog.startTime).isGreaterThan(0L)
            assertThat(prog.endTime).isGreaterThan(prog.startTime)
        }
    }

    @Test
    fun `parse_programMetadata_extracts_rating_icon_and_categories`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Metadata Show</title>
                <icon src="https://cdn.example.com/poster.jpg" />
                <category>Sports</category>
                <category>Football</category>
                <rating>
                  <value>PG-13</value>
                </rating>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream())

        assertThat(programs).hasSize(1)
        assertThat(programs.single().imageUrl).isEqualTo("https://cdn.example.com/poster.jpg")
        assertThat(programs.single().category).isEqualTo("Sports")
        assertThat(programs.single().genre).isEqualTo("Sports / Football")
        assertThat(programs.single().rating).isEqualTo("PG-13")
    }

    @Test
    fun `parse_timezoneOffset_correctUtc`() {
        // 2025-01-01 12:00:00 +03:00 == 2025-01-01 09:00:00 UTC
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0300" stop="20250101130000 +0300" channel="ch1">
                <title>Timezone Test</title>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream(), timezoneId = "Pacific/Honolulu")

        assertThat(programs).hasSize(1)
        val prog = programs[0]

        // Expected: 2025-01-01 09:00:00 UTC
        // = 2025-01-01 00:00:00 UTC (1_735_689_600_000) + 9h (32_400_000) = 1_735_722_000_000
        val expected09UTC = 1_735_722_000_000L
        assertThat(prog.startTime).isEqualTo(expected09UTC)
    }

    @Test
    fun `parse_noOffsetTimestamp_usesProvidedTimezone`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000" stop="20250101130000" channel="ch1">
                <title>Timezone Test</title>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream(), timezoneId = "America/New_York")

        assertThat(programs).hasSize(1)
        assertThat(programs.single().startTime).isEqualTo(1_735_750_800_000L)
    }

    @Test
    fun `parse_noOffsetTimestamp_isRejectedIndependentlyOfDeviceTimezone`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000" stop="20250101130000" channel="ch1">
                <title>Timezone Test</title>
              </programme>
            </tv>
        """.trimIndent()

        val original = TimeZone.getDefault()
        try {
            listOf("Asia/Jerusalem", "America/Los_Angeles").forEach { deviceZone ->
                TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
                assertThat(parser.parse(xml.byteInputStream())).isEmpty()
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `parse_explicitTimezone_appliesDstRules`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250115120000" stop="20250115130000" channel="ch1">
                <title>Winter</title>
              </programme>
              <programme start="20250715120000" stop="20250715130000" channel="ch1">
                <title>Summer</title>
              </programme>
            </tv>
        """.trimIndent()
        val zone = ZoneId.of("Europe/Amsterdam")

        val programs = parser.parse(xml.byteInputStream(), timezoneId = zone.id)

        assertThat(programs.map { it.startTime }).containsExactly(
            LocalDateTime.of(2025, 1, 15, 12, 0).atZone(zone).toInstant().toEpochMilli(),
            LocalDateTime.of(2025, 7, 15, 12, 0).atZone(zone).toInstant().toEpochMilli()
        ).inOrder()
    }

    @Test
    fun `parse_noOffsetTimestamp_rejectsInvalidTimezoneConfiguration`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000" stop="20250101130000" channel="ch1">
                <title>Timezone Test</title>
              </programme>
            </tv>
        """.trimIndent()

        val failure = runCatching {
            parser.parse(xml.byteInputStream(), timezoneId = "Mars/Olympus")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `parse_emptyStream_returnsEmpty`() {
        val xml = """<?xml version="1.0"?><tv></tv>"""

        val programs = parser.parse(xml.byteInputStream())

        assertThat(programs).isEmpty()
    }

    // ── Defensive / error handling ────────────────────────────────

    @Test
    fun `parse_missingTitle_skipsProgram`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <desc>No title here at all</desc>
              </programme>
              <programme start="20250101130000 +0000" stop="20250101140000 +0000" channel="ch1">
                <title>Valid Program</title>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream())

        // Only the program with a title should be returned
        assertThat(programs).hasSize(1)
        assertThat(programs[0].title).isEqualTo("Valid Program")
    }

    @Test
    fun `parse_malformedXml_returnsPartialResults`() {
        // Valid programs first, then broken XML at the end
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Good Program 1</title>
              </programme>
              <programme start="20250101130000 +0000" stop="20250101140000 +0000" channel="ch1">
                <title>Good Program 2</title>
              </programme>
              <programme start="20250101140000 +0000" stop="BROKEN_XML_FOLLOWS
        """.trimIndent() // deliberately truncated / broken

        val programs = parser.parse(xml.byteInputStream())

        // XmltvParser has a try/catch that returns partial results
        // At minimum, no exception should propagate
        assertThat(programs).isNotNull()
        // Depending on where the parser breaks, we expect 0–2 programs (not a crash)
        assertThat(programs.size).isAtMost(2)
    }

    @Test
    fun `parse_textWithBareAmpersand_returnsProgramInRelaxedMode`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Law &amp; Order</title>
                <desc>Tom & Jerry investigate</desc>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream())

        assertThat(programs).hasSize(1)
        assertThat(programs.single().title).isEqualTo("Law & Order")
        assertThat(programs.single().description).contains("Tom")
    }

    @Test
    fun `parse_sameInstance_isStableAcrossConcurrentCalls`() = runTest {
        val xml = """
          <?xml version="1.0"?>
          <tv>
            <programme start="20250101120000 +0300" stop="20250101130000 +0300" channel="ch1">
            <title>Concurrent Parse</title>
            </programme>
          </tv>
        """.trimIndent()

        val timestamps = (1..64).map {
          async(Dispatchers.Default) {
            parser.parse(xml.byteInputStream()).single().startTime
          }
        }.awaitAll()

        assertThat(timestamps.distinct()).containsExactly(1_735_722_000_000L)
    }

    // ── parseStreamingWithChannels ─────────────────────────────────

    @Test
    fun `parseStreamingWithChannels_yieldsChannelsAndProgrammes`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="ch1">
                <display-name>BBC One</display-name>
                <icon src="http://example.com/bbc.png"/>
              </channel>
              <channel id="ch2">
                <display-name>CNN</display-name>
              </channel>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>News</title>
                <desc>Daily news</desc>
              </programme>
              <programme start="20250101130000 +0000" stop="20250101140000 +0000" channel="ch2">
                <title>Sports</title>
              </programme>
            </tv>
        """.trimIndent()

        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()

        parser.parseStreamingWithChannels(
            inputStream = xml.byteInputStream(),
            onChannel = { channels.add(it) },
            onProgramme = { programmes.add(it) }
        )

        assertThat(channels).hasSize(2)
        assertThat(channels[0].id).isEqualTo("ch1")
        assertThat(channels[0].displayName).isEqualTo("BBC One")
        assertThat(channels[0].iconUrl).isEqualTo("http://example.com/bbc.png")
        assertThat(channels[1].id).isEqualTo("ch2")
        assertThat(channels[1].displayName).isEqualTo("CNN")
        assertThat(channels[1].iconUrl).isNull()

        assertThat(programmes).hasSize(2)
        assertThat(programmes[0].channelId).isEqualTo("ch1")
        assertThat(programmes[0].title).isEqualTo("News")
        assertThat(programmes[1].channelId).isEqualTo("ch2")
        assertThat(programmes[1].title).isEqualTo("Sports")
    }

    @Test
    fun `parseStreamingWithChannels_parsesSubtitleAndEpisodeInfo`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="ch1">
                <display-name>BBC One</display-name>
              </channel>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Doctor Who</title>
                <sub-title>The Christmas Invasion</sub-title>
                <episode-num system="xmltv_ns">1.2.0/1</episode-num>
                <desc>A holiday special</desc>
              </programme>
            </tv>
        """.trimIndent()

        val programmes = mutableListOf<XmltvProgramme>()
        parser.parseStreamingWithChannels(
            inputStream = xml.byteInputStream(),
            onChannel = { },
            onProgramme = { programmes.add(it) }
        )

        assertThat(programmes).hasSize(1)
        assertThat(programmes[0].subtitle).isEqualTo("The Christmas Invasion")
        assertThat(programmes[0].episodeInfo).isEqualTo("1.2.0/1")
    }

    @Test
    fun `parseStreamingWithChannels_emptyXml_yieldsNothing`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv></tv>
        """.trimIndent()

        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()

        parser.parseStreamingWithChannels(
            inputStream = xml.byteInputStream(),
            onChannel = { channels.add(it) },
            onProgramme = { programmes.add(it) }
        )

        assertThat(channels).isEmpty()
        assertThat(programmes).isEmpty()
    }

    @Test
    fun `parseStreamingWithChannels_textWithBareAmpersand_returnsProgramme`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="ch1">
                <display-name>BBC One</display-name>
              </channel>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Fish & Chips</title>
                <desc>Salt & vinegar special</desc>
              </programme>
            </tv>
        """.trimIndent()

        val programmes = mutableListOf<XmltvProgramme>()
        parser.parseStreamingWithChannels(
            inputStream = xml.byteInputStream(),
            onChannel = { },
            onProgramme = { programmes.add(it) }
        )

        assertThat(programmes).hasSize(1)
        assertThat(programmes.single().title).contains("Fish")
        assertThat(programmes.single().description).contains("Salt")
    }

    @Test
    fun `parseStreamingWithChannels_skipsMalformedProgrammes`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="ch1">
                <display-name>BBC One</display-name>
              </channel>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Valid News</title>
              </programme>
              <programme start="20250101130000 +0000" channel="ch1">
                <title>Missing Stop</title>
              </programme>
              <programme start="invalid" stop="20250101150000 +0000" channel="ch1">
                <title>Invalid Start</title>
              </programme>
              <programme start="20250101160000 +0000" stop="20250101160000 +0000" channel="ch1">
                <title>Zero Length</title>
              </programme>
            </tv>
        """.trimIndent()

        val programmes = mutableListOf<XmltvProgramme>()
        parser.parseStreamingWithChannels(
            inputStream = xml.byteInputStream(),
            onChannel = { },
            onProgramme = { programmes.add(it) }
        )

        assertThat(programmes).hasSize(1)
        assertThat(programmes.single().title).isEqualTo("Valid News")
    }

    @Test
    fun `parseStreamingWithChannels_enforcesChannelAndProgrammeCounts`() = runTest {
        val twoChannels = """
            <tv>
              <channel id="one"><display-name>One</display-name></channel>
              <channel id="two"><display-name>Two</display-name></channel>
            </tv>
        """.trimIndent()
        val twoProgrammes = """
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="one"><title>One</title></programme>
              <programme start="20250101130000 +0000" stop="20250101140000 +0000" channel="one"><title>Two</title></programme>
            </tv>
        """.trimIndent()

        val channelFailure = runCatching {
            parser.parseStreamingWithChannels(
                inputStream = twoChannels.byteInputStream(),
                onChannel = {},
                onProgramme = {},
                limits = XmltvIngestionLimits(maxChannels = 1)
            )
        }.exceptionOrNull()
        val programmeFailure = runCatching {
            parser.parseStreamingWithChannels(
                inputStream = twoProgrammes.byteInputStream(),
                onChannel = {},
                onProgramme = {},
                limits = XmltvIngestionLimits(maxProgrammes = 1)
            )
        }.exceptionOrNull()

        assertThat(channelFailure).isInstanceOf(XmltvLimitExceeded::class.java)
        assertThat((channelFailure as XmltvLimitExceeded).kind).isEqualTo(XmltvLimitKind.CHANNELS)
        assertThat(programmeFailure).isInstanceOf(XmltvLimitExceeded::class.java)
        assertThat((programmeFailure as XmltvLimitExceeded).kind).isEqualTo(XmltvLimitKind.PROGRAMMES)
    }

    @Test
    fun `parseStreamingWithChannels_rejectsVeryLongPersistedText`() = runTest {
        val xml = """
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="one">
                <title>News</title><desc>${"x".repeat(33)}</desc>
              </programme>
            </tv>
        """.trimIndent()

        val failure = runCatching {
            parser.parseStreamingWithChannels(
                inputStream = xml.byteInputStream(),
                onChannel = {},
                onProgramme = {},
                limits = XmltvIngestionLimits(maxFieldChars = 32)
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XmltvLimitExceeded::class.java)
        assertThat((failure as XmltvLimitExceeded).kind).isEqualTo(XmltvLimitKind.FIELD_LENGTH)
    }

    @Test
    fun `parseStreamingWithChannels_rejectsExcessiveNesting`() = runTest {
        val xml = "<tv><programme><desc><b>nested</b></desc></programme></tv>"

        val failure = runCatching {
            parser.parseStreamingWithChannels(
                inputStream = xml.byteInputStream(),
                onChannel = {},
                onProgramme = {},
                limits = XmltvIngestionLimits(maxXmlDepth = 3)
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XmltvLimitExceeded::class.java)
        assertThat((failure as XmltvLimitExceeded).kind).isEqualTo(XmltvLimitKind.XML_DEPTH)
    }

    @Test
    fun `parseStreamingWithChannels_boundsCategoriesPerProgramme`() = runTest {
        val xml = """
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="one">
                <title>News</title><category>News</category><category>Current affairs</category>
              </programme>
            </tv>
        """.trimIndent()

        val failure = runCatching {
            parser.parseStreamingWithChannels(
                inputStream = xml.byteInputStream(),
                onChannel = {},
                onProgramme = {},
                limits = XmltvIngestionLimits(maxCategoriesPerProgramme = 1)
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XmltvLimitExceeded::class.java)
        assertThat((failure as XmltvLimitExceeded).kind)
            .isEqualTo(XmltvLimitKind.CATEGORIES_PER_PROGRAMME)
    }

    @Test
    fun `maybeDecompressGzip_nonGzUrl_returnsReadableStream`() {
        val original = "hello".byteInputStream()
        val result = parser.maybeDecompressGzip("http://example.com/epg.xml", original)
      assertThat(String(result.readBytes(), Charsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `maybeDecompressGzip_detectsGzipByMagicBytesWithoutGzSuffix`() {
      val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="myepg.ch1">
          <display-name>MyEPG Channel</display-name>
          </channel>
        </tv>
      """.trimIndent()

      val result = parser.maybeDecompressGzip(
        "https://myepg.example/download?order=private&key=redacted",
        gzip(xml.toByteArray(Charsets.UTF_8)).inputStream()
      )

      assertThat(String(result.readBytes(), Charsets.UTF_8)).isEqualTo(xml)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
      val output = ByteArrayOutputStream()
      GZIPOutputStream(output).use { it.write(bytes) }
      return output.toByteArray()
    }
}
