package com.reals.backend.service

import com.reals.backend.domain.IdentityVerificationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.identity.IdentityVerificationProvider
import com.reals.backend.service.identity.IdentityVerificationRequest
import com.reals.backend.service.identity.IdentityVerificationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class IdentityVerificationServiceTest {

    @Test
    fun `provider failure returns needs review when fail on provider error is disabled`() {
        val service = IdentityVerificationService(
            provider = throwingProvider(),
            failOnProviderError = false
        )

        val result = service.verify(request())

        assertEquals(IdentityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("provider-error", result.provider)
    }

    @Test
    fun `provider failure rejects verification when fail on provider error is enabled`() {
        val service = IdentityVerificationService(
            provider = throwingProvider(),
            failOnProviderError = true
        )

        val exception = assertThrows<DomainConflictException> {
            service.verify(request())
        }

        assertEquals(DomainErrorCode.IDENTITY_VERIFICATION_PROVIDER_ERROR, exception.code)
    }

    private fun throwingProvider(): IdentityVerificationProvider =
        object : IdentityVerificationProvider {
            override fun verify(request: IdentityVerificationRequest) =
                throw RuntimeException("provider unavailable")
        }

    private fun request(): IdentityVerificationRequest =
        IdentityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            displayName = "Identity Test",
            birthDate = LocalDate.of(1995, 1, 1)
        )
}
