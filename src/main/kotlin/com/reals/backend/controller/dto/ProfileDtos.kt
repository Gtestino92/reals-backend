package com.reals.backend.controller.dto

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfileStatus
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.util.UUID

data class UpdateProfileRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val city: String? = null,
    val country: String? = null,
    val intention: Intention? = null,
    val lookingForGender: LookingForGender? = null
)

data class ReplacePhotoRequest(
    val url: String,
    val isPersonPhoto: Boolean = false,
    val isFullBody: Boolean = false
)

data class CreateProfileRequest(
    val displayName: String,
    val birthDate: LocalDate,
    val gender: Gender,
    val lookingForGender: LookingForGender,
    val intention: Intention,
    val city: String,
    val country: String,
    val bio: String? = null
)

data class AddPhotoRequest(
    val url: String,
    val position: Int,
    val isPersonPhoto: Boolean = false,
    val isFullBody: Boolean = false
)

data class ProfileResponse(
    val id: UUID,
    val userId: UUID,
    val displayName: String,
    val birthDate: LocalDate,
    val age: Int,
    val gender: Gender,
    val lookingForGender: LookingForGender,
    val intention: Intention,
    val city: String,
    val country: String,
    val bio: String?,
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
            gender = profile.gender,
            lookingForGender = profile.lookingForGender,
            intention = profile.intention,
            city = profile.city,
            country = profile.country,
            bio = profile.bio,
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
