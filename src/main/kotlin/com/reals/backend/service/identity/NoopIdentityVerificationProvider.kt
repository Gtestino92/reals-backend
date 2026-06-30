package com.reals.backend.service.identity

import com.reals.backend.domain.IdentityVerificationStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "profile.identity-verification",
    name = ["provider"],
    havingValue = "none",
    matchIfMissing = true
)
class NoopIdentityVerificationProvider : IdentityVerificationProvider {

    // MVP/local compatibility only. This does not represent real document,
    // liveness, age or fraud verification.
    override fun verify(request: IdentityVerificationRequest): IdentityVerificationResult =
        IdentityVerificationResult(
            status = IdentityVerificationStatus.VERIFIED,
            provider = "none"
        )
}
