package com.reals.backend.service.identity

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.IdentityVerificationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class NoopIdentityVerificationProviderTest {

    @Test
    fun `none provider preserves verified result outside prod`() {
        val provider = NoopIdentityVerificationProvider(
            EnvironmentExposurePolicy.forActiveProfiles("test")
        )

        val result = provider.verify(request())

        assertEquals(IdentityVerificationStatus.VERIFIED, result.status)
        assertEquals("none", result.provider)
    }

    @Test
    fun `none provider fails explicitly in prod`() {
        val provider = NoopIdentityVerificationProvider(
            EnvironmentExposurePolicy.forActiveProfiles("prod")
        )

        val exception = assertThrows<DomainConflictException> {
            provider.verify(request())
        }

        assertEquals(DomainErrorCode.IDENTITY_VERIFICATION_NOT_CONFIGURED, exception.code)
    }

    private fun request(): IdentityVerificationRequest =
        IdentityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            displayName = "Identity Test",
            birthDate = LocalDate.of(1995, 1, 1)
        )
}
