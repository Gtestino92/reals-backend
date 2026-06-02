package com.reals.backend.service.identity

import org.springframework.stereotype.Component

@Component
class NoopIdentityVerificationProvider : IdentityVerificationProvider {

    override val providerName: String = "none"

    override fun verify(request: IdentityVerificationRequest): IdentityVerificationResult =
        IdentityVerificationResult(
            verified = false,
            providerName = providerName
        )
}
