package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.util.UUID

@Service
@Transactional
class ProfileService(
    private val profileRepository: ProfileRepository,
    private val profilePhotoRepository: ProfilePhotoRepository,
    private val profilePhotoValidationService: ProfilePhotoValidationService,

    @param:Value("\${profile.photos.max-count}")
    private val maxPhotoCount: Int,

    @param:Value("\${profile.photos.required-count}")
    private val requiredPhotoCount: Int,

    @param:Value("\${profile.photos.min-person-photos}")
    private val minPersonPhotos: Int,

    @param:Value("\${profile.photos.min-full-body-photos}")
    private val minFullBodyPhotos: Int
) {

    private companion object {
        const val DISPLAY_NAME_MIN_LENGTH = 2
        const val DISPLAY_NAME_MAX_LENGTH = 100
        const val LOCATION_MAX_LENGTH = 100
        const val BIO_MAX_LENGTH = 1000
        const val PHOTO_URL_MAX_LENGTH = 512
        const val MIN_PROFILE_AGE = 18
        const val MAX_PROFILE_AGE = 99
    }

    fun findByUserId(userId: UUID): Profile? =
        profileRepository.findByUserId(userId)

    fun findByUserIds(userIds: Collection<UUID>): List<Profile> =
        profileRepository.findByUserIdIn(userIds)

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
        val normalizedDisplayName = displayName.trim()
        val normalizedCity = city.trim()
        val normalizedCountry = country.trim()
        val normalizedBio = normalizeOptionalText(bio)

        validateDisplayName(normalizedDisplayName)
        validateBirthDate(birthDate)
        validateLocation(normalizedCity, normalizedCountry)
        normalizedBio?.let { validateText("Bio", it, BIO_MAX_LENGTH) }

        check(profileRepository.findByUserId(userId) == null) {
            "User $userId already has a profile"
        }

        val profile = Profile(
            userId = userId,
            displayName = normalizedDisplayName,
            birthDate = birthDate,
            identityVerified = false,
            gender = gender,
            lookingForGender = lookingForGender,
            intention = intention,
            city = normalizedCity,
            country = normalizedCountry,
            bio = normalizedBio,
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
        isPersonPhoto: Boolean? = null,
        isFullBody: Boolean? = null
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

        val trimmedUrl = url.trim()
        validatePhotoUrl(trimmedUrl)

        // TODO: Limit client overrides for photo semantic flags to local/dev or admin tooling.
        val validation = profilePhotoValidationService.validate(
            ProfilePhotoValidationRequest(
                url = trimmedUrl
            )
        )

        return profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profileId,
                url = trimmedUrl,
                position = position,
                isPersonPhoto = isPersonPhoto ?: validation.isPersonPhoto,
                isFullBody = isFullBody ?: validation.isFullBody
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

        displayName?.trim()?.let {
            validateDisplayName(it)
            profile.displayName = it
        }

        bio?.let {
            val normalizedBio = normalizeOptionalText(it)
            normalizedBio?.let { value -> validateText("Bio", value, BIO_MAX_LENGTH) }
            profile.bio = normalizedBio
        }

        city?.trim()?.let {
            validateText("City", it, LOCATION_MAX_LENGTH)
            profile.city = it
        }

        country?.trim()?.let {
            validateText("Country", it, LOCATION_MAX_LENGTH)
            profile.country = it
        }

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
        isPersonPhoto: Boolean? = null,
        isFullBody: Boolean? = null
    ): ProfilePhoto {

        findByIdOrThrow(profileId)

        check(position in 1..maxPhotoCount) {
            "Photo position must be between 1 and $maxPhotoCount"
        }

        val existing = profilePhotoRepository.findByProfileIdAndPosition(
            profileId,
            position
        )

        if (existing != null) {
            profilePhotoRepository.delete(existing)
        }

        val trimmedUrl = url.trim()
        validatePhotoUrl(trimmedUrl)

        // TODO: Limit client overrides for photo semantic flags to local/dev or admin tooling.
        val validation = profilePhotoValidationService.validate(
            ProfilePhotoValidationRequest(
                url = trimmedUrl
            )
        )

        return profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profileId,
                url = trimmedUrl,
                position = position,
                isPersonPhoto = isPersonPhoto ?: validation.isPersonPhoto,
                isFullBody = isFullBody ?: validation.isFullBody
            )
        )
    }

    private fun validateDisplayName(displayName: String) {
        require(displayName.length in DISPLAY_NAME_MIN_LENGTH..DISPLAY_NAME_MAX_LENGTH) {
            "Display name must be between $DISPLAY_NAME_MIN_LENGTH and $DISPLAY_NAME_MAX_LENGTH characters"
        }

        requireNoControlCharacters("Display name", displayName)
    }

    private fun validateBirthDate(birthDate: LocalDate) {
        require(birthDate.isBefore(LocalDate.now())) {
            "Birth date must be in the past"
        }

        val age = Period.between(birthDate, LocalDate.now()).years

        require(age in MIN_PROFILE_AGE..MAX_PROFILE_AGE) {
            "Profile age must be between $MIN_PROFILE_AGE and $MAX_PROFILE_AGE"
        }

        // TODO: Verify user identity through a dedicated identity-verification provider.
    }

    private fun validateLocation(
        city: String,
        country: String
    ) {
        validateText("City", city, LOCATION_MAX_LENGTH)
        validateText("Country", country, LOCATION_MAX_LENGTH)
        // TODO: Validate country/city against a canonical cached location dataset.
    }

    private fun validateText(
        fieldName: String,
        value: String,
        maxLength: Int
    ) {
        require(value.isNotBlank()) {
            "$fieldName is required"
        }

        require(value.length <= maxLength) {
            "$fieldName must be at most $maxLength characters"
        }

        requireNoControlCharacters(fieldName, value)
    }

    private fun validatePhotoUrl(url: String) {
        require(url.length <= PHOTO_URL_MAX_LENGTH) {
            "Photo URL must be at most $PHOTO_URL_MAX_LENGTH characters"
        }

        requireNoControlCharacters("Photo URL", url)

        val uri =
            try {
                URI(url)
            } catch (ex: IllegalArgumentException) {
                throw IllegalArgumentException("Photo URL must be a valid HTTPS URL", ex)
            }

        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Photo URL must be a valid HTTPS URL"
        }
    }

    private fun normalizeOptionalText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }

    private fun requireNoControlCharacters(
        fieldName: String,
        value: String
    ) {
        require(value.none { it.isISOControl() }) {
            "$fieldName cannot contain control characters"
        }
    }
}
