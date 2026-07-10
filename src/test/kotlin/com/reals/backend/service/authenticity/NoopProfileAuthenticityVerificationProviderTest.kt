package com.reals.backend.service.authenticity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class NoopProfileAuthenticityVerificationProviderTest {

    @Test
    fun `none provider reports not configured without synthesizing authenticity signals`() {
        val provider = NoopProfileAuthenticityVerificationProvider()

        val result = provider.verify(request())

        assertTrue(result is ProfileAuthenticityVerificationProviderResult.NotConfigured)
        assertEquals("none", result.provider)
    }

    private fun request(): ProfileAuthenticityVerificationRequest =
        ProfileAuthenticityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            personPhotos = emptyList()
        )
}
