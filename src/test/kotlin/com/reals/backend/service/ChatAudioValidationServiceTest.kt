package com.reals.backend.service

import com.reals.backend.config.ChatAudioProperties
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatAudioValidationServiceTest {
    private val service = ChatAudioValidationService(ChatAudioProperties(enabled = true))

    @Test
    fun `valid AAC audio-only MP4 is accepted`() {
        val inspection = service.inspect(
            contentType = "audio/mp4",
            bytes = mp4Bytes(audioDuration = 1_000)
        )

        assertEquals("audio/mp4", inspection.contentType)
        assertEquals(1_000, inspection.durationMillis)
    }

    @Test
    fun `exactly sixty seconds is accepted`() {
        val inspection = service.inspect(
            contentType = "audio/mp4",
            bytes = mp4Bytes(audioDuration = 60_000)
        )

        assertEquals(60_000, inspection.durationMillis)
    }

    @Test
    fun `duration immediately over sixty seconds is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                contentType = "audio/mp4",
                bytes = mp4Bytes(audioDuration = 60_001)
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_TOO_LONG, ex.code)
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
            service.inspect("audio/mpeg", mp4Bytes(audioDuration = 1_000))
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `MP4 containing video is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                "audio/mp4",
                mp4Bytes(audioDuration = 1_000, includeVideo = true)
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `MP4 without audio is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                "audio/mp4",
                mp4Bytes(audioDuration = null, includeVideo = true)
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    @Test
    fun `unsupported audio sample entry is rejected`() {
        val ex = assertFailsWith<DomainBadRequestException> {
            service.inspect(
                "audio/mp4",
                mp4Bytes(audioDuration = 1_000, audioSampleEntry = "alac")
            )
        }

        assertEquals(DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT, ex.code)
    }

    private fun mp4Bytes(
        audioDuration: Long?,
        includeVideo: Boolean = false,
        audioSampleEntry: String = "mp4a"
    ): ByteArray =
        box(
            "ftyp",
            ascii("M4A "),
            uint32(0),
            ascii("M4A "),
            ascii("isom")
        ) + box(
            "moov",
            *buildList {
                if (audioDuration != null) {
                    add(track("soun", audioDuration, audioSampleEntry))
                }
                if (includeVideo) {
                    add(track("vide", 1_000, "avc1"))
                }
            }.toTypedArray()
        )

    private fun track(
        handlerType: String,
        duration: Long,
        sampleEntry: String
    ): ByteArray =
        box(
            "trak",
            box(
                "mdia",
                mdhd(timescale = 1_000, duration = duration),
                hdlr(handlerType),
                box(
                    "minf",
                    box(
                        "stbl",
                        stsd(sampleEntry)
                    )
                )
            )
        )

    private fun mdhd(timescale: Long, duration: Long): ByteArray =
        box(
            "mdhd",
            byteArrayOf(0, 0, 0, 0),
            uint32(0),
            uint32(0),
            uint32(timescale),
            uint32(duration),
            byteArrayOf(0, 0, 0, 0)
        )

    private fun hdlr(handlerType: String): ByteArray =
        box(
            "hdlr",
            byteArrayOf(0, 0, 0, 0),
            uint32(0),
            ascii(handlerType),
            byteArrayOf(0, 0, 0, 0)
        )

    private fun stsd(sampleEntry: String): ByteArray =
        box(
            "stsd",
            byteArrayOf(0, 0, 0, 0),
            uint32(1),
            box(sampleEntry)
        )

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val payload = payloads.fold(ByteArray(0)) { left, right -> left + right }
        return uint32(payload.size + 8L) + ascii(type) + payload
    }

    private fun uint32(value: Long): ByteArray =
        byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )

    private fun ascii(value: String): ByteArray =
        value.toByteArray(Charsets.US_ASCII)
}
