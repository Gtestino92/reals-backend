package com.reals.backend.service.identity

import com.reals.backend.domain.IdentityVerificationStatus
import java.time.LocalDate
import java.util.UUID

interface IdentityVerificationProvider {
    fun verify(request: IdentityVerificationRequest): IdentityVerificationResult
}

data class IdentityVerificationRequest(
    val userId: UUID,
    val profileId: UUID,
    val displayName: String,
    val birthDate: LocalDate
)

data class IdentityVerificationResult(
    val status: IdentityVerificationStatus,
    val provider: String,
    val reason: String? = null
)
