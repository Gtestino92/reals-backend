package com.reals.backend.service

import com.reals.backend.config.ChatAudioProperties
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatAudioValidationServiceTest {
    private val service = ChatAudioValidationService(ChatAudioProperties(enabled = true))

    @Test
    fun `valid playable AAC audio-only M4A fixture is accepted`() {
        val inspection = service.inspect(
            contentType = "audio/mp4",
            bytes = fixtureBytes("valid-aac-short.m4a")
        )

        assertEquals("audio/mp4", inspection.contentType)
        assertEquals(1_000, inspection.durationMillis)
    }

    @Test
    fun `exactly sixty seconds is accepted`() {
        val inspection = service.inspect(
            contentType = "audio/mp4",
            bytes = m4aBytes(duration = BigInteger.valueOf(60_000))
        )

        assertEquals(60_000, inspection.durationMillis)
    }

    @Test
    fun `duration immediately over sixty seconds is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                contentType = "audio/mp4",
                bytes = m4aBytes(duration = BigInteger.valueOf(60_001))
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_TOO_LONG, ex.code)
    }

    @Test
    fun `metadata-only fake MP4 is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect("audio/mp4", metadataOnlyMp4())
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `mp4a without AAC codec configuration is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                "audio/mp4",
                m4aBytes(includeEsds = false)
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `MP4 without media payload is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                "audio/mp4",
                m4aBytes(mediaPayload = ByteArray(0))
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `malformed truncated MP4 is rejected`() {
        val bytes = fixtureBytes("valid-aac-short.m4a")
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect("audio/mp4", bytes.copyOf(bytes.size - 5))
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `empty file is rejected as invalid format`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect("audio/mp4", ByteArray(0))
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `file over product size is rejected before parsing`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect("audio/mp4", ByteArray(2 * 1024 * 1024 + 1) { 1 })
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_TOO_LARGE, ex.code)
    }

    @Test
    fun `spoofed MIME is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect("audio/mpeg", fixtureBytes("valid-aac-short.m4a"))
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `MP4 containing video is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                "audio/mp4",
                m4aBytes(includeVideo = true)
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `MP4 without audio is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                "audio/mp4",
                ftyp() + box("moov", videoTrack())
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `unsupported audio sample entry is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                "audio/mp4",
                m4aBytes(audioSampleEntry = "alac")
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `mdhd version one huge duration is rejected without overflow`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                "audio/mp4",
                m4aBytes(
                    duration = BigInteger("9223372036854775808"),
                    mdhdVersion = 1
                )
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_TOO_LONG, ex.code)
    }

    private fun fixtureBytes(fileName: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/audio/$fileName")) {
            "Missing test fixture $fileName"
        }.use { it.readBytes() }

    private fun metadataOnlyMp4(): ByteArray =
        ftyp() + box(
            "moov",
            box(
                "trak",
                box(
                    "mdia",
                    mdhd(timescale = 1_000, duration = BigInteger.valueOf(1_000)),
                    hdlr("soun"),
                    box(
                        "minf",
                        box(
                            "stbl",
                            stsd("mp4a", includeEsds = true)
                        )
                    )
                )
            )
        )

    private fun m4aBytes(
        duration: BigInteger = BigInteger.valueOf(1_000),
        includeVideo: Boolean = false,
        audioSampleEntry: String = "mp4a",
        includeEsds: Boolean = true,
        mediaPayload: ByteArray = AAC_SAMPLE_PAYLOAD,
        mdhdVersion: Int = 0
    ): ByteArray {
        val ftyp = ftyp()
        val mdat = box("mdat", mediaPayload)
        val chunkOffset = ftyp.size + 8L
        return ftyp + mdat + box(
            "moov",
            *buildList {
                add(audioTrack(duration, chunkOffset, audioSampleEntry, includeEsds, mediaPayload.size, mdhdVersion))
                if (includeVideo) {
                    add(videoTrack())
                }
            }.toTypedArray()
        )
    }

    private fun audioTrack(
        duration: BigInteger,
        chunkOffset: Long,
        sampleEntry: String,
        includeEsds: Boolean,
        sampleSize: Int,
        mdhdVersion: Int
    ): ByteArray =
        track(
            handlerType = "soun",
            mdhd = mdhd(
                timescale = 1_000,
                duration = duration,
                version = mdhdVersion
            ),
            stbl = box(
                "stbl",
                stsd(sampleEntry, includeEsds),
                stsc(),
                stsz(sampleSize),
                stco(chunkOffset)
            )
        )

    private fun videoTrack(): ByteArray =
        track(
            handlerType = "vide",
            mdhd = mdhd(timescale = 1_000, duration = BigInteger.valueOf(1_000)),
            stbl = box("stbl", stsd("avc1", includeEsds = false))
        )

    private fun track(
        handlerType: String,
        mdhd: ByteArray,
        stbl: ByteArray
    ): ByteArray =
        box(
            "trak",
            box(
                "mdia",
                mdhd,
                hdlr(handlerType),
                box("minf", stbl)
            )
        )

    private fun ftyp(): ByteArray =
        box(
            "ftyp",
            ascii("M4A "),
            uint32(0),
            ascii("M4A "),
            ascii("isom")
        )

    private fun mdhd(
        timescale: Long,
        duration: BigInteger,
        version: Int = 0
    ): ByteArray =
        if (version == 1) {
            box(
                "mdhd",
                byteArrayOf(1, 0, 0, 0),
                uint64(BigInteger.ZERO),
                uint64(BigInteger.ZERO),
                uint32(timescale),
                uint64(duration),
                byteArrayOf(0, 0, 0, 0)
            )
        } else {
            box(
                "mdhd",
                byteArrayOf(0, 0, 0, 0),
                uint32(0),
                uint32(0),
                uint32(timescale),
                uint32(duration.longValueExact()),
                byteArrayOf(0, 0, 0, 0)
            )
        }

    private fun hdlr(handlerType: String): ByteArray =
        box(
            "hdlr",
            byteArrayOf(0, 0, 0, 0),
            uint32(0),
            ascii(handlerType),
            byteArrayOf(0, 0, 0, 0)
        )

    private fun stsd(
        sampleEntry: String,
        includeEsds: Boolean
    ): ByteArray =
        box(
            "stsd",
            byteArrayOf(0, 0, 0, 0),
            uint32(1),
            if (sampleEntry == "mp4a") {
                mp4aSampleEntry(includeEsds)
            } else {
                box(sampleEntry)
            }
        )

    private fun mp4aSampleEntry(includeEsds: Boolean): ByteArray =
        box(
            "mp4a",
            ByteArray(6),
            uint16(1),
            uint16(0),
            uint16(0),
            uint32(0),
            uint16(1),
            uint16(16),
            uint16(0),
            uint16(0),
            uint32(44_100L shl 16),
            if (includeEsds) esds() else ByteArray(0)
        )

    private fun esds(): ByteArray {
        val audioSpecificConfig = descriptor(0x05, byteArrayOf(0x12, 0x08))
        val decoderConfig = descriptor(
            0x04,
            byteArrayOf(0x40, 0x15),
            byteArrayOf(0, 0, 0),
            uint32(0),
            uint32(0),
            audioSpecificConfig
        )
        val esDescriptor = descriptor(
            0x03,
            uint16(1),
            byteArrayOf(0),
            decoderConfig,
            descriptor(0x06, byteArrayOf(0x02))
        )
        return box("esds", byteArrayOf(0, 0, 0, 0), esDescriptor)
    }

    private fun stsc(): ByteArray =
        box(
            "stsc",
            byteArrayOf(0, 0, 0, 0),
            uint32(1),
            uint32(1),
            uint32(1),
            uint32(1)
        )

    private fun stsz(sampleSize: Int): ByteArray =
        box(
            "stsz",
            byteArrayOf(0, 0, 0, 0),
            uint32(0),
            uint32(1),
            uint32(sampleSize.toLong())
        )

    private fun stco(chunkOffset: Long): ByteArray =
        box(
            "stco",
            byteArrayOf(0, 0, 0, 0),
            uint32(1),
            uint32(chunkOffset)
        )

    private fun descriptor(
        tag: Int,
        vararg payloads: ByteArray
    ): ByteArray {
        val payload = payloads.fold(ByteArray(0)) { left, right -> left + right }
        require(payload.size < 128) { "Test descriptor payload is too large" }
        return byteArrayOf(tag.toByte(), payload.size.toByte()) + payload
    }

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val payload = payloads.fold(ByteArray(0)) { left, right -> left + right }
        return uint32(payload.size + 8L) + ascii(type) + payload
    }

    private fun uint16(value: Int): ByteArray =
        byteArrayOf(
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )

    private fun uint32(value: Long): ByteArray =
        byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )

    private fun uint64(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return ByteArray(8 - raw.size.coerceAtMost(8)) + raw.takeLast(8).toByteArray()
    }

    private fun ascii(value: String): ByteArray =
        value.toByteArray(Charsets.US_ASCII)

    private companion object {
        val AAC_SAMPLE_PAYLOAD = byteArrayOf(
            0x21,
            0x10,
            0x04,
            0x60,
            0x8C.toByte(),
            0x1C
        )
    }
}
