package com.reals.backend.controller.dto

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfileStatus
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
    @field:Pattern(regexp = "^[^\\p{Cntrl}]*$")
    val displayName: String? = null,

    @field:Size(max = 1000)
    @field:Pattern(regexp = "^[^\\p{Cntrl}]*$")
    val bio: String? = null,

    @field:Size(min = 1, max = 100)
    @field:Pattern(regexp = "^[^\\p{Cntrl}]*$")
    val city: String? = null,

    @field:Size(min = 1, max = 100)
    @field:Pattern(regexp = "^[^\\p{Cntrl}]*$")
    val country: String? = null,

    val intention: Intention? = null,
    val lookingForGender: LookingForGender? = null,

    @field:Min(18)
    @field:Max(99)
    val preferredMinAge: Int? = null,

    @field:Min(18)
    @field:Max(99)
    val preferredMaxAge: Int? = null,

    @field:Min(1)
    @field:Max(1000)
    val maxDistanceKm: Int? = null
)

data class ReplacePhotoRequest(
    @field:NotBlank
    @field:Size(max = 512)
    @field:Pattern(regexp = "^https://\\S+$")
    val url: String,
    val isPersonPhoto: Boolean? = null,
    val isFullBody: Boolean? = null
)

data class UpdateMatchFiltersRequest(
    @field:Min(18)
    @field:Max(99)
    val preferredMinAge: Int? = null,

    @field:Min(18)
    @field:Max(99)
    val preferredMaxAge: Int? = null,

    @field:Min(1)
    @field:Max(1000)
    val maxDistanceKm: Int? = null
)

data class CreateProfileRequest(
    @field:NotBlank
    @field:Size(min = 2, max = 100)
    @field:Pattern(regexp = "^[^\\p{Cntrl}]*$")
    val displayName: String,

    @field:Past
    val birthDate: LocalDate,

    val gender: Gender,
    val lookingForGender: LookingForGender,
    val intention: Intention,

    @field:NotBlank
    @field:Size(max = 100)
    @field:Pattern(regexp = "^[^\\p{Cntrl}]*$")
    val city: String,

    @field:NotBlank
    @field:Size(max = 100)
    @field:Pattern(regexp = "^[^\\p{Cntrl}]*$")
    val country: String,

    @field:Size(max = 1000)
    @field:Pattern(regexp = "^[^\\p{Cntrl}]*$")
    val bio: String? = null,

    @field:Min(18)
    @field:Max(99)
    val preferredMinAge: Int? = null,

    @field:Min(18)
    @field:Max(99)
    val preferredMaxAge: Int? = null,

    @field:Min(1)
    @field:Max(1000)
    val maxDistanceKm: Int? = null
)

data class AddPhotoRequest(
    @field:NotBlank
    @field:Size(max = 512)
    @field:Pattern(regexp = "^https://\\S+$")
    val url: String,

    @field:Min(1)
    val position: Int,

    val isPersonPhoto: Boolean? = null,
    val isFullBody: Boolean? = null
)

data class ProfileResponse(
    val id: UUID,
    val userId: UUID,
    val displayName: String,
    val birthDate: LocalDate,
    val age: Int,
    val identityVerified: Boolean,
    val gender: Gender,
    val lookingForGender: LookingForGender,
    val intention: Intention,
    val city: String,
    val country: String,
    val bio: String?,
    val preferredMinAge: Int?,
    val preferredMaxAge: Int?,
    val maxDistanceKm: Int?,
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
    val profileId: UUID,
    val url: String,
    val storageProvider: PhotoStorageProvider,
    val position: Int,
    val isPersonPhoto: Boolean,
    val isFullBody: Boolean,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(photo: ProfilePhoto) = PhotoResponse(
            id = photo.id,
            profileId = photo.profileId,
            url = photo.url,
            storageProvider = photo.storageProvider,
            position = photo.position,
            isPersonPhoto = photo.isPersonPhoto,
            isFullBody = photo.isFullBody,
            createdAt = photo.createdAt
        )
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
    val photos: List<PhotoResponse>
) {
    companion object {
        fun from(
            profile: Profile,
            photos: List<ProfilePhoto>
        ) = VisualProfileResponse(
            profileId = profile.id,
            displayName = profile.displayName,
            age = Period.between(profile.birthDate, LocalDate.now()).years,
            bio = profile.bio,
            photos = photos
                .sortedBy { it.position }
                .map { PhotoResponse.from(it) }
        )
    }
}
