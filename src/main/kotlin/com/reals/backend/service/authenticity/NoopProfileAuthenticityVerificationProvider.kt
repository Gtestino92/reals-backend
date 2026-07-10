package com.reals.backend.service.authenticity

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "profile.authenticity-verification",
    name = ["provider"],
    havingValue = "none",
    matchIfMissing = true
)
class NoopProfileAuthenticityVerificationProvider : ProfileAuthenticityVerificationProvider {

    override fun verify(
        request: ProfileAuthenticityVerificationRequest
    ): ProfileAuthenticityVerificationProviderResult =
        ProfileAuthenticityVerificationProviderResult.NotConfigured(provider = "none")
}
