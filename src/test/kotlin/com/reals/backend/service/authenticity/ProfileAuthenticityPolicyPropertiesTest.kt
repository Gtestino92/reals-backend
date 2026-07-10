package com.reals.backend.service.authenticity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProfileAuthenticityPolicyPropertiesTest {

    @Test
    fun `policy configuration defaults to three matched and zero contradictory photos`() {
        val properties = ProfileAuthenticityPolicyProperties()

        assertEquals(3, properties.minMatchedPersonPhotos)
        assertEquals(0, properties.maxContradictoryPersonPhotos)
    }

    @Test
    fun `invalid min matched configuration fails validation`() {
        assertThrows<IllegalArgumentException> {
            ProfileAuthenticityPolicyProperties(minMatchedPersonPhotos = 0)
        }
    }

    @Test
    fun `negative max contradictory configuration fails validation`() {
        assertThrows<IllegalArgumentException> {
            ProfileAuthenticityPolicyProperties(maxContradictoryPersonPhotos = -1)
        }
    }
}
