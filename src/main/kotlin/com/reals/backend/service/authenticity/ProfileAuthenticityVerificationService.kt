package com.reals.backend.service.authenticity

import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ProfileAuthenticityVerificationService(
    private val provider: ProfileAuthenticityVerificationProvider,

    @param:Value("\${profile.authenticity-verification.fail-on-provider-error:false}")
    private val failOnProviderError: Boolean
) {

    fun verify(request: ProfileAuthenticityVerificationRequest): ProfileAuthenticityVerificationResult =
        try {
            provider.verify(request)
        } catch (ex: DomainException) {
            throw ex
        } catch (ex: Exception) {
            if (failOnProviderError) {
                throw DomainConflictException(
                    code = DomainErrorCode.AUTHENTICITY_VERIFICATION_PROVIDER_ERROR,
                    message = "Profile authenticity verification provider failed"
                )
            }

            ProfileAuthenticityVerificationResult(
                status = ProfileAuthenticityVerificationStatus.NEEDS_REVIEW,
                provider = "provider-error",
                reason = ex.message
            )
        }
}
