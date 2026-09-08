package com.reals.backend.service

import com.reals.backend.config.ChatAudioProperties
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        if (parsed.audioTracks.any { it.sampleEntry != "mp4a" || !it.hasAacCodecConfiguration }) {
            throw invalidFormat("Audio codec is not supported")
        }
        if (parsed.audioTracks.any { !it.hasNonEmptyMediaSample(parsed.mdatRanges) }) {
            throw invalidFormat("Audio messages require a non-empty media sample")
        }

        if (parsed.audioTracks.any { it.duration <= BigInteger.ZERO || it.timescale <= 0 }) {
            throw invalidFormat("Audio duration is invalid")
        }
        val longestAudioTrack = parsed.audioTracks.maxWith(::compareDuration)
        if (durationExceeds(longestAudioTrack.duration, longestAudioTrack.timescale, properties.maxDurationMillis)) {
            throw DomainBadRequestException(
                code = DomainErrorCode.CHAT_AUDIO_TOO_LONG,
                message = "Audio duration exceeds ${properties.maxDurationMillis} milliseconds"
            )
        }

        return ChatAudioInspection(
            contentType = declaredContentType!!,
            sizeBytes = bytes.size.toLong(),
            durationMillis = ceilDurationMillis(longestAudioTrack.duration, longestAudioTrack.timescale)
        )
    }

    private fun invalidFormat(message: String): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.CHAT_AUDIO_INVALID_FORMAT,
            message = message
        )

    private fun durationExceeds(
        duration: BigInteger,
        timescale: Long,
        maxDurationMillis: Long
    ): Boolean =
        duration.multiply(BI_1000) >
            BigInteger.valueOf(maxDurationMillis).multiply(BigInteger.valueOf(timescale))

    private fun ceilDurationMillis(
        duration: BigInteger,
        timescale: Long
    ): Long {
        val (quotient, remainder) = duration.multiply(BI_1000)
            .divideAndRemainder(BigInteger.valueOf(timescale))
        return if (remainder.signum() == 0) {
            quotient.longValueExact()
        } else {
            quotient.add(BigInteger.ONE).longValueExact()
        }
    }

    private companion object {
        val BI_1000: BigInteger = BigInteger.valueOf(1000)
    }
}

private data class ParsedMp4(
    var isMpeg4: Boolean = false,
    var videoTrackCount: Int = 0,
    val audioTracks: MutableList<ParsedTrack> = mutableListOf(),
    val mdatRanges: MutableList<ByteRange> = mutableListOf()
)

private data class ParsedTrack(
    var handlerType: String? = null,
    var timescale: Long = 0,
    var duration: BigInteger = BigInteger.ZERO,
    var sampleEntry: String? = null,
    var hasAacCodecConfiguration: Boolean = false,
    var hasSampleToChunk: Boolean = false,
    val chunkOffsets: MutableList<Long> = mutableListOf(),
    val sampleSizes: MutableList<Long> = mutableListOf()
) {
    fun hasNonEmptyMediaSample(mdatRanges: List<ByteRange>): Boolean {
        if (!hasSampleToChunk || chunkOffsets.isEmpty() || sampleSizes.none { it > 0 }) {
            return false
        }
        val firstNonEmptySampleSize = sampleSizes.first { it > 0 }
        return chunkOffsets.any { offset ->
            mdatRanges.any { range ->
                offset >= range.start &&
                    offset < range.end &&
                    offset + firstNonEmptySampleSize > offset &&
                    offset + firstNonEmptySampleSize <= range.end
            }
        }
    }
}

private data class ByteRange(
    val start: Long,
    val end: Long
)

private fun compareDuration(
    left: ParsedTrack,
    right: ParsedTrack
): Int =
    left.duration.multiply(BigInteger.valueOf(right.timescale))
        .compareTo(right.duration.multiply(BigInteger.valueOf(left.timescale)))

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
                        uint64Big(position + 8).longValueExact()
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
                "mdat" -> parsed.mdatRanges += ByteRange(contentStart.toLong(), contentEnd.toLong())
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
                "stsd" -> track?.let { inspectStsd(contentStart, contentEnd, it) }
                "stsc" -> track?.hasSampleToChunk = hasSampleToChunk(contentStart, contentEnd)
                "stsz" -> track?.sampleSizes?.addAll(stszSampleSizes(contentStart, contentEnd))
                "stco" -> track?.chunkOffsets?.addAll(stcoChunkOffsets(contentStart, contentEnd))
                "co64" -> track?.chunkOffsets?.addAll(co64ChunkOffsets(contentStart, contentEnd))
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
            track.duration = uint64Big(start + 24)
        } else {
            track.timescale = uint32(start + 12)
            track.duration = BigInteger.valueOf(uint32(start + 16))
        }
    }

    private fun inspectStsd(start: Int, end: Int, track: ParsedTrack) {
        if (end - start < 16) {
            throw IllegalArgumentException("Malformed stsd box")
        }
        val entryCount = uint32(start + 4)
        if (entryCount < 1) {
            return
        }
        var position = start + 8
        repeat(entryCount.toInt()) {
            val box = readBox(position, end)
            track.sampleEntry = box.type
            if (box.type == "mp4a") {
                inspectAudioSampleEntry(box.contentStart, box.contentEnd, track)
            }
            position = box.end
        }
    }

    private fun inspectAudioSampleEntry(start: Int, end: Int, track: ParsedTrack) {
        if (end - start < AUDIO_SAMPLE_ENTRY_HEADER_SIZE) {
            throw IllegalArgumentException("Malformed mp4a sample entry")
        }
        parseSampleEntryBoxes(start + AUDIO_SAMPLE_ENTRY_HEADER_SIZE, end, track)
    }

    private fun parseSampleEntryBoxes(start: Int, end: Int, track: ParsedTrack) {
        var position = start
        while (position + HEADER_SIZE <= end) {
            val box = readBox(position, end)
            if (box.type == "esds") {
                track.hasAacCodecConfiguration = hasSupportedAacEsds(box.contentStart, box.contentEnd)
            }
            position = box.end
        }
    }

    private fun hasSupportedAacEsds(start: Int, end: Int): Boolean {
        if (end - start < 8) {
            return false
        }
        val state = EsdsState()
        scanDescriptors(start + 4, end, state)
        return state.objectTypeIndication == AAC_OBJECT_TYPE_INDICATION &&
            state.audioObjectType in SUPPORTED_AAC_AUDIO_OBJECT_TYPES
    }

    private fun scanDescriptors(start: Int, end: Int, state: EsdsState) {
        var position = start
        while (position < end) {
            val descriptor = readDescriptor(position, end) ?: return
            when (descriptor.tag) {
                ES_DESCRIPTOR_TAG -> {
                    if (descriptor.contentStart + 3 <= descriptor.contentEnd) {
                        scanDescriptors(descriptor.contentStart + 3, descriptor.contentEnd, state)
                    }
                }
                DECODER_CONFIG_DESCRIPTOR_TAG -> {
                    if (descriptor.contentStart + 13 <= descriptor.contentEnd) {
                        state.objectTypeIndication = byteAt(descriptor.contentStart)
                        scanDescriptors(descriptor.contentStart + 13, descriptor.contentEnd, state)
                    }
                }
                DECODER_SPECIFIC_INFO_TAG -> {
                    state.audioObjectType = audioObjectType(descriptor.contentStart, descriptor.contentEnd)
                }
            }
            position = descriptor.contentEnd
        }
    }

    private fun audioObjectType(start: Int, end: Int): Int? {
        if (start >= end) {
            return null
        }
        val firstByte = byteAt(start)
        val initialObjectType = firstByte ushr 3
        return if (initialObjectType == 31) {
            if (start + 1 >= end) null else 32 + ((firstByte and 0x07) shl 3) + (byteAt(start + 1) ushr 5)
        } else {
            initialObjectType
        }
    }

    private fun hasSampleToChunk(start: Int, end: Int): Boolean {
        if (end - start < 8) {
            throw IllegalArgumentException("Malformed stsc box")
        }
        return uint32(start + 4) > 0
    }

    private fun stszSampleSizes(start: Int, end: Int): List<Long> {
        if (end - start < 12) {
            throw IllegalArgumentException("Malformed stsz box")
        }
        val defaultSampleSize = uint32(start + 4)
        val sampleCount = uint32(start + 8)
        if (sampleCount <= 0) {
            return emptyList()
        }
        if (defaultSampleSize > 0) {
            return List(sampleCount.toInt()) { defaultSampleSize }
        }
        if (sampleCount > Int.MAX_VALUE || end - start < 12 + sampleCount * 4) {
            throw IllegalArgumentException("Malformed stsz sample table")
        }
        return (0 until sampleCount.toInt()).map { index ->
            uint32(start + 12 + index * 4)
        }
    }

    private fun stcoChunkOffsets(start: Int, end: Int): List<Long> {
        if (end - start < 8) {
            throw IllegalArgumentException("Malformed stco box")
        }
        val entryCount = uint32(start + 4)
        if (entryCount > Int.MAX_VALUE || end - start < 8 + entryCount * 4) {
            throw IllegalArgumentException("Malformed stco box")
        }
        return (0 until entryCount.toInt()).map { index ->
            uint32(start + 8 + index * 4)
        }
    }

    private fun co64ChunkOffsets(start: Int, end: Int): List<Long> {
        if (end - start < 8) {
            throw IllegalArgumentException("Malformed co64 box")
        }
        val entryCount = uint32(start + 4)
        if (entryCount > Int.MAX_VALUE || end - start < 8 + entryCount * 8) {
            throw IllegalArgumentException("Malformed co64 box")
        }
        return (0 until entryCount.toInt()).map { index ->
            uint64Big(start + 8 + index * 8).longValueExact()
        }
    }

    private fun readBox(position: Int, end: Int): ParsedBox {
        if (position + HEADER_SIZE > end) {
            throw IllegalArgumentException("Truncated MP4 box")
        }
        val size32 = uint32(position)
        val type = ascii(position + 4, 4)
        var headerSize = HEADER_SIZE
        val boxSize =
            when (size32) {
                0L -> (end - position).toLong()
                1L -> {
                    if (position + 16 > end) throw IllegalArgumentException("Truncated extended MP4 box")
                    headerSize = 16
                    uint64Big(position + 8).longValueExact()
                }
                else -> size32
            }
        if (boxSize < headerSize || position + boxSize > end) {
            throw IllegalArgumentException("Malformed MP4 box")
        }
        return ParsedBox(
            type = type,
            contentStart = position + headerSize,
            contentEnd = (position + boxSize).toInt(),
            end = (position + boxSize).toInt()
        )
    }

    private fun readDescriptor(position: Int, end: Int): Descriptor? {
        if (position + 2 > end) {
            return null
        }
        val tag = byteAt(position)
        var length = 0
        var cursor = position + 1
        repeat(MAX_DESCRIPTOR_LENGTH_BYTES) {
            if (cursor >= end) return null
            val next = byteAt(cursor)
            length = (length shl 7) or (next and 0x7F)
            cursor += 1
            if ((next and 0x80) == 0) {
                val contentEnd = cursor + length
                return if (contentEnd <= end) {
                    Descriptor(tag, cursor, contentEnd)
                } else {
                    null
                }
            }
        }
        return null
    }

    private fun uint32(offset: Int): Long =
        buffer(offset, 4).int.toLong() and 0xFFFF_FFFFL

    private fun uint64Big(offset: Int): BigInteger =
        BigInteger(1, bytes.copyOfRange(offset, offset + 8))

    private fun ascii(offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun buffer(offset: Int, length: Int): ByteBuffer =
        ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)

    private fun byteAt(offset: Int): Int =
        bytes[offset].toInt() and 0xFF

    private data class ParsedBox(
        val type: String,
        val contentStart: Int,
        val contentEnd: Int,
        val end: Int
    )

    private data class Descriptor(
        val tag: Int,
        val contentStart: Int,
        val contentEnd: Int
    )

    private data class EsdsState(
        var objectTypeIndication: Int? = null,
        var audioObjectType: Int? = null
    )

    private companion object {
        const val HEADER_SIZE = 8
        const val AUDIO_SAMPLE_ENTRY_HEADER_SIZE = 28
        const val MAX_DESCRIPTOR_LENGTH_BYTES = 4
        const val ES_DESCRIPTOR_TAG = 0x03
        const val DECODER_CONFIG_DESCRIPTOR_TAG = 0x04
        const val DECODER_SPECIFIC_INFO_TAG = 0x05
        const val AAC_OBJECT_TYPE_INDICATION = 0x40
        val MPEG4_BRANDS = setOf("M4A ", "M4B ", "mp42", "mp41", "isom", "iso2")
        val SUPPORTED_AAC_AUDIO_OBJECT_TYPES = setOf(2, 5, 29)
    }
}
