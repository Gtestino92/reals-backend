package com.reals.backend.service.contacts

import com.reals.backend.config.SharedContactsProperties
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import javax.crypto.Mac

class ContactSelection(val numbers: List<String>, val defaultRegion: String? = null)

class ContactRequestError(val status: Int, val code: String, message: String) : RuntimeException(message)

@Component
class ContactPseudonymizer(private val properties: SharedContactsProperties, private val phones: ContactPhoneNormalizer) {
    fun identifiers(selection: ContactSelection): Set<ByteBuffer> {
        if (selection.numbers.size > properties.maxRaw) throw ContactRequestError(413, "CONTACT_INPUT_LIMIT", "Select fewer entries")
        val mac = Mac.getInstance("HmacSHA256").apply { init(requireNotNull(properties.key)) }
        val distinct = mutableSetOf<ByteBuffer>()
        var invalid = 0
        for (raw in selection.numbers) {
            val normalized = if (raw.length <= properties.maxInputLength) phones.normalize(raw, selection.defaultRegion) else null
            if (normalized == null) {
                invalid++
            } else {
                distinct.add(ByteBuffer.wrap(mac.doFinal(("${properties.keyVersion}:" + normalized).toByteArray(Charsets.US_ASCII))))
            }
        }
        if (invalid > 0) throw ContactRequestError(400, "INVALID_CONTACT_SELECTION", "Unable to normalize $invalid entries")
        if (distinct.size > properties.maxDistinct) throw ContactRequestError(413, "CONTACT_SET_LIMIT", "Select at most ${properties.maxDistinct} distinct numbers")
        return distinct
    }
}
