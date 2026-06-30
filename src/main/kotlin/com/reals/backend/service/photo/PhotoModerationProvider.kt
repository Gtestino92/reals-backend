package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoModerationStatus
import java.util.UUID

interface PhotoModerationProvider {
    fun moderate(request: PhotoModerationRequest): PhotoModerationResult
}

data class PhotoModerationRequest(
    val userId: UUID,
    val profileId: UUID,
    val photoId: UUID,
    val contentType: String,
    val bytes: ByteArray
)

data class PhotoModerationResult(
    val status: PhotoModerationStatus,
    val provider: String,
    val reason: String? = null
)
