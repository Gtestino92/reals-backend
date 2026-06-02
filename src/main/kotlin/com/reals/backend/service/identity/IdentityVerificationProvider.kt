package com.reals.backend.service.identity

import java.time.LocalDate
import java.util.UUID

interface IdentityVerificationProvider {

    val providerName: String

    fun verify(request: IdentityVerificationRequest): IdentityVerificationResult
}

data class IdentityVerificationRequest(
    val userId: UUID,
    val displayName: String,
    val birthDate: LocalDate
)

data class IdentityVerificationResult(
    val verified: Boolean,
    val providerName: String,
    val externalReference: String? = null
)
