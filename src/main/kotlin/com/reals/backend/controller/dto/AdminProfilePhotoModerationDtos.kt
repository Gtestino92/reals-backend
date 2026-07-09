package com.reals.backend.controller.dto

import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.service.photo.AdminPhotoModerationDecision
import com.reals.backend.service.photo.AdminProfilePhotoModerationReview
import com.reals.backend.validation.PlainText
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class AdminProfilePhotoModerationReviewResponse(
    val photoId: UUID,
    val profileId: UUID,
    val userId: UUID,
    val displayName: String,
    val position: Int,
    val readUrl: String,
    val validationStatus: PhotoValidationStatus,
    val moderationStatus: PhotoModerationStatus,
    val isPersonPhoto: Boolean,
    val isFullBody: Boolean,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(review: AdminProfilePhotoModerationReview): AdminProfilePhotoModerationReviewResponse =
            AdminProfilePhotoModerationReviewResponse(
                photoId = review.photo.id,
                profileId = review.profile.id,
                userId = review.profile.userId,
                displayName = review.profile.displayName,
                position = review.photo.position,
                readUrl = review.readUrl,
                validationStatus = review.photo.validationStatus,
                moderationStatus = review.photo.moderationStatus,
                isPersonPhoto = review.photo.isPersonPhoto,
                isFullBody = review.photo.isFullBody,
                createdAt = review.photo.createdAt
            )
    }
}

data class AdminPhotoModerationResolutionRequest(
    @field:NotNull
    val decision: AdminPhotoModerationDecision?,

    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val notes: String? = null
) {
    fun normalizedNotes(): String? =
        notes?.trim()?.ifBlank { null }
}
