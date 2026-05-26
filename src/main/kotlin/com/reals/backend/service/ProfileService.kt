package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class ProfileService(
    private val profileRepository: ProfileRepository,
    private val profilePhotoRepository: ProfilePhotoRepository,

    @Value("\${profile.photos.max-count}")
    private val maxPhotoCount: Int,

    @Value("\${profile.photos.required-count}")
    private val requiredPhotoCount: Int,

    @Value("\${profile.photos.min-person-photos}")
    private val minPersonPhotos: Int,

    @Value("\${profile.photos.min-full-body-photos}")
    private val minFullBodyPhotos: Int
) {

    fun findByUserId(userId: UUID): Profile? =
        profileRepository.findByUserId(userId)

    fun findByIdOrThrow(profileId: UUID): Profile =
        profileRepository.findById(profileId)
            .orElseThrow {
                NoSuchElementException("Profile not found: $profileId")
            }

    fun createProfile(
        userId: UUID,
        displayName: String,
        birthDate: LocalDate,
        gender: Gender,
        lookingForGender: LookingForGender,
        intention: Intention,
        city: String,
        country: String,
        bio: String? = null
    ): Profile {

        check(profileRepository.findByUserId(userId) == null) {
            "User $userId already has a profile"
        }

        val profile = Profile(
            userId = userId,
            displayName = displayName.trim(),
            birthDate = birthDate,
            gender = gender,
            lookingForGender = lookingForGender,
            intention = intention,
            city = city.trim(),
            country = country.trim(),
            bio = bio?.trim(),
            status = ProfileStatus.DRAFT
        )

        return profileRepository.save(profile)
    }

    fun activateProfile(profileId: UUID): Profile {

        val profile = findByIdOrThrow(profileId)

        check(
            profile.status == ProfileStatus.DRAFT ||
                profile.status == ProfileStatus.INACTIVE
        ) {
            "Profile $profileId cannot be activated from status ${profile.status}"
        }

        validatePhotosOrThrow(profileId)

        profile.status = ProfileStatus.ACTIVE
        profile.updatedAt = OffsetDateTime.now()

        return profileRepository.save(profile)
    }

    fun addPhoto(
        profileId: UUID,
        url: String,
        position: Int,
        isPersonPhoto: Boolean,
        isFullBody: Boolean
    ): ProfilePhoto {

        findByIdOrThrow(profileId)

        check(position in 1..maxPhotoCount) {
            "Photo position must be between 1 and $maxPhotoCount"
        }

        check(profilePhotoRepository.findByProfileIdAndPosition(profileId, position) == null) {
            "Photo position $position is already used"
        }

        val currentCount = profilePhotoRepository.countByProfileId(profileId)

        check(currentCount < maxPhotoCount) {
            "Profile $profileId already has the maximum number of photos ($maxPhotoCount)"
        }

        return profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profileId,
                url = url.trim(),
                position = position,
                isPersonPhoto = isPersonPhoto,
                isFullBody = isFullBody
            )
        )
    }

    private fun validatePhotosOrThrow(profileId: UUID) {

        val total = profilePhotoRepository.countByProfileId(profileId)
        val personCount =
            profilePhotoRepository.countByProfileIdAndIsPersonPhotoTrue(profileId)
        val fullBody =
            profilePhotoRepository.countByProfileIdAndIsFullBodyTrue(profileId)

        check(total >= requiredPhotoCount) {
            "Profile must have at least $requiredPhotoCount photos"
        }

        check(personCount >= minPersonPhotos) {
            "Profile must have at least $minPersonPhotos person photos"
        }

        check(fullBody >= minFullBodyPhotos) {
            "Profile must have at least $minFullBodyPhotos full-body photos"
        }
    }

    fun isEligibleForMatchmaking(profileId: UUID): Boolean {
        val profile = findByIdOrThrow(profileId)
        return profile.status == ProfileStatus.ACTIVE
    }

    fun getPhotos(profileId: UUID): List<ProfilePhoto> {
        findByIdOrThrow(profileId)
        return profilePhotoRepository.findByProfileId(profileId)
            .sortedBy { it.position }
    }

    fun updateProfile(
        profileId: UUID,
        displayName: String? = null,
        bio: String? = null,
        city: String? = null,
        country: String? = null,
        intention: Intention? = null,
        lookingForGender: LookingForGender? = null
    ): Profile {

        val profile = findByIdOrThrow(profileId)

        displayName?.let { profile.displayName = it.trim() }
        bio?.let { profile.bio = it.trim() }
        city?.let { profile.city = it.trim() }
        country?.let { profile.country = it.trim() }
        intention?.let { profile.intention = it }
        lookingForGender?.let { profile.lookingForGender = it }

        profile.updatedAt = OffsetDateTime.now()

        return profileRepository.save(profile)
    }

    fun deletePhoto(
        profileId: UUID,
        position: Int
    ): Profile {

        val profile = findByIdOrThrow(profileId)

        val existing = profilePhotoRepository.findByProfileIdAndPosition(
            profileId,
            position
        ) ?: throw NoSuchElementException(
            "Photo not found for profile $profileId at position $position"
        )

        profilePhotoRepository.delete(existing)

        if (
            profile.status == ProfileStatus.ACTIVE &&
            profilePhotoRepository.countByProfileId(profileId) < requiredPhotoCount
        ) {
            profile.status = ProfileStatus.DRAFT
        }

        profile.updatedAt = OffsetDateTime.now()

        return profileRepository.save(profile)
    }

    fun replacePhoto(
        profileId: UUID,
        position: Int,
        url: String,
        isPersonPhoto: Boolean,
        isFullBody: Boolean
    ): ProfilePhoto {

        findByIdOrThrow(profileId)

        val existing = profilePhotoRepository.findByProfileIdAndPosition(
            profileId,
            position
        )

        if (existing != null) {
            profilePhotoRepository.delete(existing)
        }

        return profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profileId,
                url = url.trim(),
                position = position,
                isPersonPhoto = isPersonPhoto,
                isFullBody = isFullBody
            )
        )
    }
}
