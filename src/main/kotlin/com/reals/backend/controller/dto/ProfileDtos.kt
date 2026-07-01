package com.reals.backend.controller.dto

import com.reals.backend.domain.Gender
import com.reals.backend.domain.IdentityVerificationStatus
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.validation.PlainText
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.util.UUID

data class UpdateProfileRequest(
    @field:Size(min = 2, max = 100)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val displayName: String? = null,

    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val bio: String? = null,

    @field:Size(min = 1, max = 100)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val city: String? = null,

    @field:Size(min = 1, max = 100)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val country: String? = null,

    val intention: Intention? = null,
    val lookingForGender: LookingForGender? = null
)

data class UpdateMatchFiltersRequest(
    @field:Min(18)
    @field:Max(99)
    val preferredMinAge: Int,

    @field:Min(18)
    @field:Max(99)
    val preferredMaxAge: Int,

    @field:Min(1)
    @field:Max(1000)
    val maxDistanceKm: Int
)

data class ReorderProfilePhotosRequest(
    @field:Valid
    @field:Size(min = 1, max = 9)
    val placements: List<PhotoPlacementRequest>
)

data class PhotoPlacementRequest(
    val photoId: UUID,

    @field:Min(1)
    @field:Max(9)
    val position: Int
)

data class CreateProfileRequest(
    @field:NotBlank
    @field:Size(min = 2, max = 100)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val displayName: String,

    @field:Past
    val birthDate: LocalDate,

    val gender: Gender,
    val lookingForGender: LookingForGender,
    val intention: Intention,

    @field:NotBlank
    @field:Size(max = 100)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val city: String,

    @field:NotBlank
    @field:Size(max = 100)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val country: String,

    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val bio: String? = null,

    @field:Min(18)
    @field:Max(99)
    val preferredMinAge: Int,

    @field:Min(18)
    @field:Max(99)
    val preferredMaxAge: Int,

    @field:Min(1)
    @field:Max(1000)
    val maxDistanceKm: Int
)

data class ProfileResponse(
    val id: UUID,
    val userId: UUID,
    val displayName: String,
    val birthDate: LocalDate,
    val age: Int,
    val identityVerified: Boolean,
    val identityVerificationStatus: IdentityVerificationStatus,
    val gender: Gender,
    val lookingForGender: LookingForGender,
    val intention: Intention,
    val city: String,
    val country: String,
    val bio: String?,
    val preferredMinAge: Int,
    val preferredMaxAge: Int,
    val maxDistanceKm: Int,
    val status: ProfileStatus,
    val photoCount: Int,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    companion object {
        fun from(
            profile: Profile,
            photoCount: Int = 0
        ) = ProfileResponse(
            id = profile.id,
            userId = profile.userId,
            displayName = profile.displayName,
            birthDate = profile.birthDate,
            age = Period.between(profile.birthDate, LocalDate.now()).years,
            identityVerified = profile.identityVerified,
            identityVerificationStatus = profile.identityVerificationStatus,
            gender = profile.gender,
            lookingForGender = profile.lookingForGender,
            intention = profile.intention,
            city = profile.city,
            country = profile.country,
            bio = profile.bio,
            preferredMinAge = profile.preferredMinAge,
            preferredMaxAge = profile.preferredMaxAge,
            maxDistanceKm = profile.maxDistanceKm,
            status = profile.status,
            photoCount = photoCount,
            createdAt = profile.createdAt,
            updatedAt = profile.updatedAt
        )
    }
}

data class PhotoResponse(
    val id: UUID,
    val url: String,
    val position: Int,
    val isPersonPhoto: Boolean,
    val isFullBody: Boolean,
    val validationStatus: PhotoValidationStatus,
    val moderationStatus: PhotoModerationStatus
) {
    companion object {
        fun from(
            photo: ProfilePhoto,
            url: String
        ): PhotoResponse {
            return PhotoResponse(
                id = photo.id,
                url = url,
                position = photo.position,
                isPersonPhoto = photo.isPersonPhoto,
                isFullBody = photo.isFullBody,
                validationStatus = photo.validationStatus,
                moderationStatus = photo.moderationStatus
            )
        }
    }
}


/**
 * Partial profile revealed during the visual phase.
 * Contains only what should be visible at this stage:
 * display name, age, bio, and photos.
 * Does NOT expose userId to prevent cross-referencing outside the app.
 */
data class VisualProfileResponse(
    val profileId: UUID,
    val displayName: String,
    val age: Int,
    val bio: String?,
    val photos: List<PhotoResponse>,
    val myPersonalMessageSubmitted: Boolean,
    val partnerPersonalMessageSubmitted: Boolean,
    val partnerPersonalMessageRead: Boolean,
    val decisionRequiresPartnerPersonalMessageRead: Boolean,
    val visualExpiresAt: OffsetDateTime?
) {
    companion object {
        fun from(
            profile: Profile,
            photos: List<PhotoResponse>,
            myPersonalMessageSubmitted: Boolean,
            partnerPersonalMessageSubmitted: Boolean,
            partnerPersonalMessageRead: Boolean,
            decisionRequiresPartnerPersonalMessageRead: Boolean,
            visualExpiresAt: OffsetDateTime?
        ) = VisualProfileResponse(
            profileId = profile.id,
            displayName = profile.displayName,
            age = Period.between(profile.birthDate, LocalDate.now()).years,
            bio = profile.bio,
            photos = photos.sortedBy { it.position },
            myPersonalMessageSubmitted = myPersonalMessageSubmitted,
            partnerPersonalMessageSubmitted = partnerPersonalMessageSubmitted,
            partnerPersonalMessageRead = partnerPersonalMessageRead,
            decisionRequiresPartnerPersonalMessageRead = decisionRequiresPartnerPersonalMessageRead,
            visualExpiresAt = visualExpiresAt
        )
    }
}
