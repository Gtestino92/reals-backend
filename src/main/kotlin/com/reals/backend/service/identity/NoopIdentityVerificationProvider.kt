package com.reals.backend.service.identity

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.IdentityVerificationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "profile.identity-verification",
    name = ["provider"],
    havingValue = "none",
    matchIfMissing = true
)
class NoopIdentityVerificationProvider(
    private val environmentExposurePolicy: EnvironmentExposurePolicy
) : IdentityVerificationProvider {

    // MVP/local compatibility only. This does not represent real document,
    // liveness, age or fraud verification.
    override fun verify(request: IdentityVerificationRequest): IdentityVerificationResult {
        if (environmentExposurePolicy.isProduction()) {
            throw DomainConflictException(
                code = DomainErrorCode.IDENTITY_VERIFICATION_NOT_CONFIGURED,
                message = "Identity verification is not configured"
            )
        }

        return IdentityVerificationResult(
            status = IdentityVerificationStatus.VERIFIED,
            provider = "none"
        )
    }
}
