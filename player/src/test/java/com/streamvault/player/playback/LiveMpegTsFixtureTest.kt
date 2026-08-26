@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ts.TsExtractor
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A deterministic raw transport-stream fixture large enough to cross the historical live
 * stuck window. It deliberately changes continuity, PID, and discontinuity metadata while
 * remaining a direct TS byte stream rather than an HLS playlist or segment sequence.
 */
@RunWith(RobolectricTestRunner::class)
class LiveMpegTsFixtureTest {

    @Test
    fun `single pmt policy accepts a long lived raw ts fixture as live and unknown duration`() {
        val fixture = longLivedRawMpegTsFixture(durationSeconds = 180)
        val sniffExtractor = TsExtractor(DIRECT_LIVE_MPEG_TS_POLICY.media3ExtractorMode)
        val sniffInput = DefaultExtractorInput(ByteArrayDataReader(fixture), 0L, fixture.size.toLong())

        assertThat(sniffExtractor.sniff(sniffInput)).isTrue()

        val extractor = TsExtractor(DIRECT_LIVE_MPEG_TS_POLICY.media3ExtractorMode)
        val input = DefaultExtractorInput(ByteArrayDataReader(fixture), 0L, fixture.size.toLong())
        val positionHolder = androidx.media3.extractor.PositionHolder()
        extractor.init(ExtractorOutput.PLACEHOLDER)
        while (extractor.read(input, positionHolder) != Extractor.RESULT_END_OF_INPUT) {
            // Consume the complete fixture so the assertion covers sustained input, not only sniffing.
        }

        assertThat(fixture.size).isEqualTo(180 * 50 * TS_PACKET_SIZE)
        assertThat(input.position).isEqualTo(fixture.size.toLong())
        assertThat(fixture.countPacketsWithPid(PMT_PID_ONE)).isGreaterThan(1)
        assertThat(fixture.countPacketsWithPid(PMT_PID_TWO)).isGreaterThan(1)
        assertThat(fixture.countDiscontinuityPackets()).isGreaterThan(1)
        assertThat(DIRECT_LIVE_MPEG_TS_POLICY.duration)
            .isEqualTo(LiveMpegTsPolicy.DurationPolicy.LIVE_UNKNOWN)
    }

    private class ByteArrayDataReader(private val bytes: ByteArray) : DataReader {
        private var position = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private companion object {
        const val TS_PACKET_SIZE = 188
        const val PAT_PID = 0x0000
        const val PMT_PID_ONE = 0x0100
        const val PMT_PID_TWO = 0x0101
        const val VIDEO_PID_ONE = 0x0200
        const val VIDEO_PID_TWO = 0x0201

        fun longLivedRawMpegTsFixture(durationSeconds: Int): ByteArray {
            require(durationSeconds >= 120)
            val packets = ArrayList<ByteArray>(durationSeconds * 50)
            var patContinuity = 0
            var pmtOneContinuity = 0
            var pmtTwoContinuity = 0
            var videoOneContinuity = 0
            var videoTwoContinuity = 0

            repeat(durationSeconds) { second ->
                packets += tsPacket(
                    pid = PAT_PID,
                    payloadUnitStart = true,
                    continuityCounter = patContinuity++ and 0x0f,
                    payload = patSection()
                )
                packets += tsPacket(
                    pid = PMT_PID_ONE,
                    payloadUnitStart = true,
                    continuityCounter = pmtOneContinuity++ and 0x0f,
                    payload = pmtSection(programNumber = 1, pcrPid = VIDEO_PID_ONE, videoPid = VIDEO_PID_ONE)
                )
                packets += tsPacket(
                    pid = PMT_PID_TWO,
                    payloadUnitStart = true,
                    continuityCounter = pmtTwoContinuity++ and 0x0f,
                    payload = pmtSection(programNumber = 2, pcrPid = VIDEO_PID_TWO, videoPid = VIDEO_PID_TWO)
                )

                repeat(47) { packetInSecond ->
                    val useSecondProgram = packetInSecond % 2 == 1
                    val pid = if (useSecondProgram) VIDEO_PID_TWO else VIDEO_PID_ONE
                    val continuity = if (useSecondProgram) {
                        videoTwoContinuity++ and 0x0f
                    } else {
                        videoOneContinuity++ and 0x0f
                    }
                    packets += tsPacket(
                        pid = pid,
                        payloadUnitStart = packetInSecond == 0,
                        continuityCounter = continuity,
                        discontinuity = second > 0 && packetInSecond == 0,
                        payload = videoPesPayload(second, packetInSecond)
                    )
                }
            }

            return ByteArray(packets.size * TS_PACKET_SIZE).also { result ->
                packets.forEachIndexed { packetIndex, packet ->
                    packet.copyInto(result, packetIndex * TS_PACKET_SIZE)
                }
                result
            }
        }

        private fun tsPacket(
            pid: Int,
            payloadUnitStart: Boolean,
            continuityCounter: Int,
            discontinuity: Boolean = false,
            payload: ByteArray
        ): ByteArray {
            val packet = ByteArray(TS_PACKET_SIZE) { 0xff.toByte() }
            packet[0] = 0x47
            packet[1] = (((if (payloadUnitStart) 0x40 else 0) or (pid shr 8)) and 0xff).toByte()
            packet[2] = (pid and 0xff).toByte()

            if (discontinuity) {
                packet[3] = (0x30 or (continuityCounter and 0x0f)).toByte()
                packet[4] = 1
                packet[5] = 0x80.toByte()
                payload.copyInto(packet, destinationOffset = 6, startIndex = 0, endIndex = minOf(payload.size, 182))
            } else {
                packet[3] = (0x10 or (continuityCounter and 0x0f)).toByte()
                payload.copyInto(packet, destinationOffset = 4, startIndex = 0, endIndex = minOf(payload.size, 184))
            }
            return packet
        }

        private fun patSection(): ByteArray {
            val sectionWithoutCrc = byteArrayOf(
                0x00,
                0xb0.toByte(), 0x11,
                0x00, 0x01,
                0xc1.toByte(), 0x00, 0x00,
                0x00, 0x01, 0xe1.toByte(), 0x00,
                0x00, 0x02, 0xe1.toByte(), 0x01
            )
            return sectionWithoutCrc + mpegCrc32(sectionWithoutCrc)
        }

        private fun pmtSection(programNumber: Int, pcrPid: Int, videoPid: Int): ByteArray {
            val sectionWithoutCrc = byteArrayOf(
                0x02,
                0xb0.toByte(), 0x12,
                (programNumber shr 8).toByte(), programNumber.toByte(),
                0xc1.toByte(), 0x00, 0x00,
                (0xe0 or (pcrPid shr 8)).toByte(), pcrPid.toByte(),
                0xf0.toByte(), 0x00,
                0x1b,
                (0xe0 or (videoPid shr 8)).toByte(), videoPid.toByte(),
                0xf0.toByte(), 0x00
            )
            return sectionWithoutCrc + mpegCrc32(sectionWithoutCrc)
        }

        private fun videoPesPayload(second: Int, packetInSecond: Int): ByteArray = byteArrayOf(
            0x00, 0x00, 0x01, 0xe0.toByte(),
            0x00, 0x00,
            0x80.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x01, 0x09,
            0xf0.toByte(),
            (second and 0xff).toByte(),
            (packetInSecond and 0xff).toByte()
        )

        private fun mpegCrc32(bytes: ByteArray): ByteArray {
            var crc = -0x1
            bytes.forEach { value ->
                crc = crc xor ((value.toInt() and 0xff) shl 24)
                repeat(8) {
                    crc = if ((crc and Int.MIN_VALUE) != 0) {
                        (crc shl 1) xor 0x04c11db7
                    } else {
                        crc shl 1
                    }
                }
            }
            return byteArrayOf(
                (crc ushr 24).toByte(),
                (crc ushr 16).toByte(),
                (crc ushr 8).toByte(),
                crc.toByte()
            )
        }

        private fun ByteArray.countPacketsWithPid(pid: Int): Int =
            asSequence()
                .withIndex()
                .filter { (index, value) ->
                    index % TS_PACKET_SIZE == 0 && value.toInt() and 0xff == 0x47
                }
                .count { (index, _) ->
                    val high = this[index + 1].toInt() and 0x1f
                    val low = this[index + 2].toInt() and 0xff
                    (high shl 8) or low == pid
                }

        private fun ByteArray.countDiscontinuityPackets(): Int =
            asSequence()
                .withIndex()
                .filter { (index, value) ->
                    index % TS_PACKET_SIZE == 0 && value.toInt() and 0xff == 0x47
                }
                .count { (index, _) ->
                    val adaptationAndPayload = this[index + 3].toInt() and 0x30
                    adaptationAndPayload == 0x30 &&
                        this[index + 4].toInt() and 0xff > 0 &&
                        this[index + 5].toInt() and 0x80 != 0
                }
        }
    }
