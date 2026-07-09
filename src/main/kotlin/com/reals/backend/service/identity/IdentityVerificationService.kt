package com.reals.backend.service.identity

import com.reals.backend.domain.IdentityVerificationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class IdentityVerificationService(
    private val provider: IdentityVerificationProvider,

    @param:Value("\${profile.identity-verification.fail-on-provider-error:false}")
    private val failOnProviderError: Boolean
) {

    fun verify(request: IdentityVerificationRequest): IdentityVerificationResult =
        try {
            provider.verify(request)
        } catch (ex: DomainException) {
            throw ex
        } catch (ex: Exception) {
            if (failOnProviderError) {
                throw DomainConflictException(
                    code = DomainErrorCode.IDENTITY_VERIFICATION_PROVIDER_ERROR,
                    message = "Identity verification provider failed"
                )
            }

            IdentityVerificationResult(
                status = IdentityVerificationStatus.NEEDS_REVIEW,
                provider = "provider-error",
                reason = ex.message
            )
        }
}
