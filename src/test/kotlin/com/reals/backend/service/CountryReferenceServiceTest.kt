package com.reals.backend.service

import com.reals.backend.service.exception.DomainBadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.text.Collator
import java.util.Locale

class CountryReferenceServiceTest {

    private val service = CountryReferenceService()

    @Test
    fun `country list is non empty and contains unique uppercase alpha two codes`() {
        val countries = service.getCountries()

        assertTrue(countries.isNotEmpty())
        assertEquals(countries.size, countries.map { it.code }.toSet().size)
        assertTrue(countries.all { it.code.length == 2 })
        assertTrue(countries.all { it.code == it.code.uppercase(Locale.ROOT) })
    }

    @Test
    fun `AR exists with Spanish display name`() {
        val argentina = service.getCountries().firstOrNull { it.code == "AR" }

        assertTrue(argentina != null)
        assertTrue(argentina!!.displayName.isNotBlank())
    }

    @Test
    fun `countries are ordered by Spanish display name and code tie breaker`() {
        val collator = Collator.getInstance(Locale.forLanguageTag("es"))
        val expected = service.getCountries().sortedWith { left, right ->
            val displayNameComparison = collator.compare(left.displayName, right.displayName)
            if (displayNameComparison != 0) {
                displayNameComparison
            } else {
                left.code.compareTo(right.code)
            }
        }

        assertEquals(expected, service.getCountries())
    }

    @Test
    fun `country code normalization accepts case and surrounding whitespace`() {
        assertEquals("AR", service.normalizeAndValidateCountryCode("AR"))
        assertEquals("AR", service.normalizeAndValidateCountryCode("ar"))
        assertEquals("AR", service.normalizeAndValidateCountryCode(" ar "))
    }

    @Test
    fun `invalid country code values are rejected`() {
        listOf("Argentina", "ARG", "ZZ", "").forEach { value ->
            assertThrows<DomainBadRequestException> {
                service.normalizeAndValidateCountryCode(value)
            }
        }
    }
}
