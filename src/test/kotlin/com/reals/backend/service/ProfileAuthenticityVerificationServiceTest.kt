package com.reals.backend.service

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.service.authenticity.ProfileAuthenticityPhotoCandidate
import com.reals.backend.service.authenticity.ProfileAuthenticityPhotoComparison
import com.reals.backend.service.authenticity.ProfileAuthenticityPhotoComparisonOutcome
import com.reals.backend.service.authenticity.ProfileAuthenticityPolicy
import com.reals.backend.service.authenticity.ProfileAuthenticityPolicyProperties
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationProvider
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationProviderResult
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationRequest
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationService
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationSignals
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ProfileAuthenticityVerificationServiceTest {

    @Test
    fun `provider failure returns needs review when fail on provider error is disabled`() {
        val service = service(
            provider = throwingProvider(),
            failOnProviderError = false
        )

        val result = service.verify(request())

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("provider-error", result.provider)
    }

    @Test
    fun `provider failure rejects verification when fail on provider error is enabled`() {
        val service = service(
            provider = throwingProvider(),
            failOnProviderError = true
        )

        val exception = assertThrows<DomainConflictException> {
            service.verify(request())
        }

        assertEquals(DomainErrorCode.AUTHENTICITY_VERIFICATION_PROVIDER_ERROR, exception.code)
    }

    @Test
    fun `provider failure result returns needs review when fail on provider error is disabled`() {
        val service = service(
            provider = resultProvider(
                ProfileAuthenticityVerificationProviderResult.ProviderFailure(
                    provider = "test",
                    reason = "provider unavailable"
                )
            ),
            failOnProviderError = false
        )

        val result = service.verify(request())

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("test", result.provider)
        assertEquals("provider unavailable", result.reason)
    }

    @Test
    fun `domain exceptions from provider are propagated when fail on provider error is disabled`() {
        val domainException = DomainConflictException(
            code = DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED,
            message = "Profile authenticity verification is not configured"
        )
        val service = service(
            provider = domainThrowingProvider(domainException),
            failOnProviderError = false
        )

        val exception = assertThrows<DomainConflictException> {
            service.verify(request())
        }

        assertSame(domainException, exception)
        assertEquals(DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED, exception.code)
    }

    @Test
    fun `malformed provider output returns needs review when fail on provider error is disabled`() {
        val request = request(personPhotoIds = photoIds(3))
        val service = service(
            provider = resultProvider(
                ProfileAuthenticityVerificationProviderResult.Success(
                    signals(
                        listOf(
                            ProfileAuthenticityPhotoComparison(
                                photoId = UUID.randomUUID(),
                                outcome = ProfileAuthenticityPhotoComparisonOutcome.MATCHED
                            )
                        )
                    )
                )
            ),
            failOnProviderError = false
        )

        val result = service.verify(request)

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("test", result.provider)
        assertEquals("Profile authenticity verification provider returned malformed output", result.reason)
    }

    @Test
    fun `malformed provider output rejects verification when fail on provider error is enabled`() {
        val service = service(
            provider = resultProvider(
                ProfileAuthenticityVerificationProviderResult.Success(
                    signals(
                        listOf(
                            ProfileAuthenticityPhotoComparison(
                                photoId = UUID.randomUUID(),
                                outcome = ProfileAuthenticityPhotoComparisonOutcome.MATCHED
                            )
                        )
                    )
                )
            ),
            failOnProviderError = true
        )

        val exception = assertThrows<DomainConflictException> {
            service.verify(request(personPhotoIds = photoIds(3)))
        }

        assertEquals(DomainErrorCode.AUTHENTICITY_VERIFICATION_PROVIDER_ERROR, exception.code)
    }

    @Test
    fun `none provider outside prod returns verified even with zero candidates`() {
        val service = service(
            provider = resultProvider(ProfileAuthenticityVerificationProviderResult.NotConfigured(provider = "none")),
            activeProfile = "test"
        )

        val result = service.verify(request(personPhotoIds = emptyList()))

        assertEquals(ProfileAuthenticityVerificationStatus.VERIFIED, result.status)
        assertEquals("none", result.provider)
    }

    @Test
    fun `none provider in prod returns not configured error`() {
        val service = service(
            provider = resultProvider(ProfileAuthenticityVerificationProviderResult.NotConfigured(provider = "none")),
            activeProfile = "prod"
        )

        val exception = assertThrows<DomainConflictException> {
            service.verify(request(personPhotoIds = emptyList()))
        }

        assertEquals(DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED, exception.code)
    }

    @Test
    fun `successful provider signals are evaluated by Reals policy`() {
        val ids = photoIds(3)
        val service = service(
            provider = resultProvider(
                ProfileAuthenticityVerificationProviderResult.Success(
                    signals(
                        ids.map {
                            ProfileAuthenticityPhotoComparison(
                                photoId = it,
                                outcome = ProfileAuthenticityPhotoComparisonOutcome.MATCHED
                            )
                        }
                    )
                )
            )
        )

        val result = service.verify(request(personPhotoIds = ids))

        assertEquals(ProfileAuthenticityVerificationStatus.VERIFIED, result.status)
        assertEquals("test", result.provider)
    }

    private fun service(
        provider: ProfileAuthenticityVerificationProvider,
        failOnProviderError: Boolean = false,
        activeProfile: String = "test"
    ): ProfileAuthenticityVerificationService =
        ProfileAuthenticityVerificationService(
            provider = provider,
            policy = ProfileAuthenticityPolicy(ProfileAuthenticityPolicyProperties()),
            environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles(activeProfile),
            failOnProviderError = failOnProviderError
        )

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

    private fun resultProvider(
        result: ProfileAuthenticityVerificationProviderResult
    ): ProfileAuthenticityVerificationProvider =
        object : ProfileAuthenticityVerificationProvider {
            override fun verify(request: ProfileAuthenticityVerificationRequest) = result
        }

    private fun request(
        personPhotoIds: List<UUID> = emptyList()
    ): ProfileAuthenticityVerificationRequest =
        ProfileAuthenticityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            personPhotos = personPhotoIds.mapIndexed { index, photoId ->
                ProfileAuthenticityPhotoCandidate(
                    photoId = photoId,
                    photoVersion = index.toLong(),
                    storageKey = "profile/$photoId.jpg"
                )
            }
        )

    private fun signals(
        comparisons: List<ProfileAuthenticityPhotoComparison>
    ): ProfileAuthenticityVerificationSignals =
        ProfileAuthenticityVerificationSignals(
            provider = "test",
            liveReferenceAccepted = true,
            photoComparisons = comparisons
        )

    private fun photoIds(count: Int): List<UUID> =
        (1..count).map { UUID.randomUUID() }
}
