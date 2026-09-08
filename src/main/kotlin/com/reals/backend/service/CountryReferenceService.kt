package com.reals.backend.service

import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.stereotype.Service
import java.text.Collator
import java.util.Collections
import java.util.Locale

data class CountryReference(
    val code: String,
    val displayName: String
)

@Service
class CountryReferenceService {

    private val spanishLocale: Locale = Locale.forLanguageTag("es")
    private val countries: List<CountryReference>
    private val countryCodes: Set<String>

    init {
        val collator = Collator.getInstance(spanishLocale)
        val displayNameComparator = Comparator<CountryReference> { left, right ->
            val displayNameComparison = collator.compare(left.displayName, right.displayName)
            if (displayNameComparison != 0) {
                displayNameComparison
            } else {
                left.code.compareTo(right.code)
            }
        }

        val builtCountries = Locale.getISOCountries()
            .map { code ->
                val normalizedCode = code.uppercase(Locale.ROOT)
                CountryReference(
                    code = normalizedCode,
                    displayName = Locale.Builder()
                        .setRegion(normalizedCode)
                        .build()
                        .getDisplayCountry(spanishLocale)
                )
            }
            .distinctBy { it.code }
            .sortedWith(displayNameComparator)
        countries = Collections.unmodifiableList(builtCountries)
        countryCodes = Collections.unmodifiableSet(builtCountries.mapTo(linkedSetOf()) { it.code })
    }

    fun getCountries(): List<CountryReference> = countries

    fun normalizeAndValidateCountryCode(value: String): String {
        val normalized = value.trim().uppercase(Locale.ROOT)
        if (normalized !in countryCodes) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_COUNTRY,
                message = "Profile countryCode must be a valid ISO 3166-1 alpha-2 country code"
            )
        }
        return normalized
    }
}
