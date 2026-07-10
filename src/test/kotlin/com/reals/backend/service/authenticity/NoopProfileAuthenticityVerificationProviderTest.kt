package com.reals.backend.service.authenticity

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class NoopProfileAuthenticityVerificationProviderTest {

    @Test
    fun `none provider preserves verified result outside prod`() {
        val provider = NoopProfileAuthenticityVerificationProvider(
            EnvironmentExposurePolicy.forActiveProfiles("test")
        )

        val result = provider.verify(request())

        assertEquals(ProfileAuthenticityVerificationStatus.VERIFIED, result.status)
        assertEquals("none", result.provider)
    }

    @Test
    fun `none provider fails explicitly in prod`() {
        val provider = NoopProfileAuthenticityVerificationProvider(
            EnvironmentExposurePolicy.forActiveProfiles("prod")
        )

        val exception = assertThrows<DomainConflictException> {
            provider.verify(request())
        }

        assertEquals(DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED, exception.code)
    }

    private fun request(): ProfileAuthenticityVerificationRequest =
        ProfileAuthenticityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            personPhotos = emptyList()
        )
}
