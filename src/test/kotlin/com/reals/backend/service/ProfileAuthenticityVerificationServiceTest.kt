package com.reals.backend.service

import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationProvider
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationRequest
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ProfileAuthenticityVerificationServiceTest {

    @Test
    fun `provider failure returns needs review when fail on provider error is disabled`() {
        val service = ProfileAuthenticityVerificationService(
            provider = throwingProvider(),
            failOnProviderError = false
        )

        val result = service.verify(request())

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("provider-error", result.provider)
    }

    @Test
    fun `provider failure rejects verification when fail on provider error is enabled`() {
        val service = ProfileAuthenticityVerificationService(
            provider = throwingProvider(),
            failOnProviderError = true
        )

        val exception = assertThrows<DomainConflictException> {
            service.verify(request())
        }

        assertEquals(DomainErrorCode.AUTHENTICITY_VERIFICATION_PROVIDER_ERROR, exception.code)
    }

    @Test
    fun `domain exceptions from provider are propagated when fail on provider error is disabled`() {
        val domainException = DomainConflictException(
            code = DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED,
            message = "Profile authenticity verification is not configured"
        )
        val service = ProfileAuthenticityVerificationService(
            provider = domainThrowingProvider(domainException),
            failOnProviderError = false
        )

        val exception = assertThrows<DomainConflictException> {
            service.verify(request())
        }

        assertSame(domainException, exception)
        assertEquals(DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED, exception.code)
    }

    private fun throwingProvider(): ProfileAuthenticityVerificationProvider =
        object : ProfileAuthenticityVerificationProvider {
            override fun verify(request: ProfileAuthenticityVerificationRequest) =
                throw RuntimeException("provider unavailable")
        }

    private fun domainThrowingProvider(
        exception: DomainConflictException
    ): ProfileAuthenticityVerificationProvider =
        object : ProfileAuthenticityVerificationProvider {
            override fun verify(request: ProfileAuthenticityVerificationRequest) =
                throw exception
        }

    private fun request(): ProfileAuthenticityVerificationRequest =
        ProfileAuthenticityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            personPhotos = emptyList()
        )
}
