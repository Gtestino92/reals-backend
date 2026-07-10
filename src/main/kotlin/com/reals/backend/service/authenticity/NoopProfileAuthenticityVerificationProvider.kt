package com.reals.backend.service.authenticity

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "profile.authenticity-verification",
    name = ["provider"],
    havingValue = "none",
    matchIfMissing = true
)
class NoopProfileAuthenticityVerificationProvider(
    private val environmentExposurePolicy: EnvironmentExposurePolicy
) : ProfileAuthenticityVerificationProvider {

    // MVP/local compatibility only. This does not represent liveness, face
    // comparison, legal identity, document verification or age assurance.
    override fun verify(
        request: ProfileAuthenticityVerificationRequest
    ): ProfileAuthenticityVerificationResult {
        if (environmentExposurePolicy.isProduction()) {
            throw DomainConflictException(
                code = DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED,
                message = "Profile authenticity verification is not configured"
            )
        }

        return ProfileAuthenticityVerificationResult(
            status = ProfileAuthenticityVerificationStatus.VERIFIED,
            provider = "none"
        )
    }
}
