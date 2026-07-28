package com.reals.backend.service

import com.reals.backend.config.ChatAudioProperties
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

data class ChatAudioInspection(
    val contentType: String,
    val sizeBytes: Long,
    val durationMillis: Long
)

@Service
class ChatAudioValidationService(
    private val properties: ChatAudioProperties
) {
    fun inspect(
        contentType: String?,
        bytes: ByteArray
    ): ChatAudioInspection {
        val declaredContentType = contentType?.lowercase()?.trim()
        if (declaredContentType !in properties.allowedContentTypes) {
            throw invalidFormat("Unsupported audio content type")
        }
        if (bytes.isEmpty()) {
            throw invalidFormat("Audio file is empty")
        }
        if (bytes.size > properties.maxFileSizeBytes) {
            throw DomainBadRequestException(
                code = DomainErrorCode.CHAT_AUDIO_TOO_LARGE,
                message = "Audio file exceeds maximum size"
            )
        }

        val parsed =
            try {
                Mp4AudioInspector(bytes).inspect()
            } catch (_: RuntimeException) {
                throw invalidFormat("Audio file is malformed or truncated")
            }
        if (!parsed.isMpeg4) {
            throw invalidFormat("Audio file is not a valid MPEG-4 container")
        }
        if (parsed.videoTrackCount > 0) {
            throw invalidFormat("Audio messages must not contain video tracks")
        }
        if (parsed.audioTracks.isEmpty()) {
            throw invalidFormat("Audio messages require one AAC audio track")
        }
        if (parsed.audioTracks.any { it.sampleEntry != "mp4a" }) {
            throw invalidFormat("Audio codec is not supported")
        }

        val longestAudioTrack = parsed.audioTracks.maxBy { it.duration.toDouble() / it.timescale.toDouble() }
        if (longestAudioTrack.duration <= 0 || longestAudioTrack.timescale <= 0) {
            throw invalidFormat("Audio duration is invalid")
        }
        if (longestAudioTrack.duration * 1000L > properties.maxDurationMillis * longestAudioTrack.timescale) {
            throw DomainBadRequestException(
                code = DomainErrorCode.CHAT_AUDIO_TOO_LONG,
                message = "Audio duration exceeds ${properties.maxDurationMillis} milliseconds"
            )
        }

        return ChatAudioInspection(
            contentType = declaredContentType!!,
            sizeBytes = bytes.size.toLong(),
            durationMillis = ceil(longestAudioTrack.duration * 1000.0 / longestAudioTrack.timescale).toLong()
        )
    }

    private fun invalidFormat(message: String): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT,
            message = message
        )
}

private data class ParsedMp4(
    var isMpeg4: Boolean = false,
    var videoTrackCount: Int = 0,
    val audioTracks: MutableList<ParsedTrack> = mutableListOf()
)

private data class ParsedTrack(
    var handlerType: String? = null,
    var timescale: Long = 0,
    var duration: Long = 0,
    var sampleEntry: String? = null
)

private class Mp4AudioInspector(
    private val bytes: ByteArray
) {
    private val parsed = ParsedMp4()

    fun inspect(): ParsedMp4 {
        if (bytes.size < 16) {
            throw IllegalArgumentException("MP4 file is too small")
        }
        parseBoxes(0, bytes.size, null)
        return parsed
    }

    private fun parseBoxes(
        start: Int,
        end: Int,
        track: ParsedTrack?
    ) {
        var position = start
        while (position + HEADER_SIZE <= end) {
            val size32 = uint32(position)
            val type = ascii(position + 4, 4)
            var headerSize = HEADER_SIZE
            val boxSize =
                when (size32) {
                    0L -> (end - position).toLong()
                    1L -> {
                        if (position + 16 > end) throw IllegalArgumentException("Truncated extended MP4 box")
                        headerSize = 16
                        uint64(position + 8)
                    }
                    else -> size32
                }
            if (boxSize < headerSize || position + boxSize > end) {
                throw IllegalArgumentException("Malformed MP4 box")
            }

            val contentStart = position + headerSize
            val contentEnd = (position + boxSize).toInt()
            when (type) {
                "ftyp" -> inspectFtyp(contentStart, contentEnd)
                "moov", "mdia", "minf", "stbl" -> parseBoxes(contentStart, contentEnd, track)
                "trak" -> {
                    val childTrack = ParsedTrack()
                    parseBoxes(contentStart, contentEnd, childTrack)
                    when (childTrack.handlerType) {
                        "soun" -> parsed.audioTracks += childTrack
                        "vide" -> parsed.videoTrackCount += 1
                    }
                }
                "hdlr" -> track?.handlerType = hdlrType(contentStart, contentEnd)
                "mdhd" -> track?.let { inspectMdhd(contentStart, contentEnd, it) }
                "stsd" -> track?.sampleEntry = stsdSampleEntry(contentStart, contentEnd)
            }
            position += boxSize.toInt()
        }
    }

    private fun inspectFtyp(start: Int, end: Int) {
        if (end - start < 8) {
            throw IllegalArgumentException("Malformed ftyp box")
        }
        val brands = buildList {
            add(ascii(start, 4))
            var position = start + 8
            while (position + 4 <= end) {
                add(ascii(position, 4))
                position += 4
            }
        }
        parsed.isMpeg4 = brands.any { it in MPEG4_BRANDS }
    }

    private fun hdlrType(start: Int, end: Int): String {
        if (end - start < 12) {
            throw IllegalArgumentException("Malformed hdlr box")
        }
        return ascii(start + 8, 4)
    }

    private fun inspectMdhd(start: Int, end: Int, track: ParsedTrack) {
        if (end - start < 24) {
            throw IllegalArgumentException("Malformed mdhd box")
        }
        val version = bytes[start].toInt() and 0xFF
        if (version == 1) {
            if (end - start < 36) throw IllegalArgumentException("Malformed mdhd version 1 box")
            track.timescale = uint32(start + 20)
            track.duration = uint64(start + 24)
        } else {
            track.timescale = uint32(start + 12)
            track.duration = uint32(start + 16)
        }
    }

    private fun stsdSampleEntry(start: Int, end: Int): String? {
        if (end - start < 16) {
            throw IllegalArgumentException("Malformed stsd box")
        }
        val entryCount = uint32(start + 4)
        if (entryCount < 1) {
            return null
        }
        return ascii(start + 12, 4)
    }

    private fun uint32(offset: Int): Long =
        buffer(offset, 4).int.toLong() and 0xFFFF_FFFFL

    private fun uint64(offset: Int): Long =
        buffer(offset, 8).long

    private fun ascii(offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun buffer(offset: Int, length: Int): ByteBuffer =
        ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)

    private companion object {
        const val HEADER_SIZE = 8
        val MPEG4_BRANDS = setOf("M4A ", "M4B ", "mp42", "mp41", "isom", "iso2")
    }
}
