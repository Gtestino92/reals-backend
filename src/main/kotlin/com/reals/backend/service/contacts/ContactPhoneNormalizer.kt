package com.reals.backend.service.contacts

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.springframework.stereotype.Component
import java.util.Locale

@Component
class ContactPhoneNormalizer {
    private val phones = PhoneNumberUtil.getInstance()
    private val dialString = Regex("""\+?[0-9(][0-9 ()\-.]*""")

    fun normalize(input: String, region: String?): String? {
        val value = input.trim()
        if (!dialString.matches(value)) return null
        val country = if (value.startsWith("+")) "ZZ" else
            region?.takeIf { it.length == 2 }?.uppercase(Locale.ROOT)?.takeIf { it in phones.supportedRegions } ?: return null
        val number = try {
            phones.parse(value, country)
        } catch (_: NumberParseException) {
            return null
        }
        if (!phones.isValidNumber(number)) return null
        return phones.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
    }
}
