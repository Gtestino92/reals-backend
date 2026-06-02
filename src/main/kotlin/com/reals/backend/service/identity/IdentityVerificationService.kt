package com.reals.backend.service.identity

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class IdentityVerificationService(
    providers: List<IdentityVerificationProvider>,

    @param:Value("\${identity-verification.provider:none}")
    providerName: String
) {

    private val selectedProvider: IdentityVerificationProvider =
        providers.associateBy { it.providerName.lowercase() }
            .let { availableProviders ->
                val requestedProvider = providerName.trim()
                    .takeIf { it.isNotBlank() }
                    ?: "none"

                availableProviders[requestedProvider.lowercase()]
                    ?: error(
                        "Unsupported identity-verification provider '$requestedProvider'. " +
                            "Available providers: ${availableProviders.keys.sorted().joinToString()}"
                    )
            }

    fun verify(request: IdentityVerificationRequest): IdentityVerificationResult =
        selectedProvider.verify(request)
}
