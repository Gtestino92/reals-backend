package com.reals.backend.service

import com.reals.backend.config.s3.ProfilePhotoStorageProperties
import com.reals.backend.controller.dto.PhotoResponse
import com.reals.backend.domain.*
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.identity.IdentityVerificationRequest
import com.reals.backend.service.identity.IdentityVerificationService
import com.reals.backend.validation.PlainText
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.util.*

@Service
@Transactional
class ProfileService(
    private val profileRepository: ProfileRepository,
    private val profilePhotoRepository: ProfilePhotoRepository,
    private val profilePhotoValidationService: ProfilePhotoValidationService,
    private val identityVerificationService: IdentityVerificationService,
    private val storageService: S3StorageService,
    private val profilePhotoStorageProperties: ProfilePhotoStorageProperties,

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
        bio: String? = null,
        preferredMinAge: Int,
        preferredMaxAge: Int,
        maxDistanceKm: Int
    ): Profile {
        val normalizedDisplayName = displayName.trim()
        val normalizedCity = city.trim()
        val normalizedCountry = country.trim()
        val normalizedBio = normalizeOptionalText(bio)

        validateDisplayName(normalizedDisplayName)
        validateBirthDate(birthDate)
        validateLocation(normalizedCity, normalizedCountry)
        normalizedBio?.let { validateText("Bio", it, BIO_MAX_LENGTH) }
        validateDynamicMatchFilters(
            preferredMinAge = preferredMinAge,
            preferredMaxAge = preferredMaxAge,
            maxDistanceKm = maxDistanceKm
        )

        if (profileRepository.findByUserId(userId) != null) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_ALREADY_EXISTS,
                message = "User already has a profile"
            )
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
            preferredMinAge = preferredMinAge,
            preferredMaxAge = preferredMaxAge,
            maxDistanceKm = maxDistanceKm,
            status = ProfileStatus.DRAFT
        )

        return profileRepository.save(profile)
    }

    fun verifyIdentity(profileId: UUID): Profile {
        val profile = findByIdOrThrow(profileId)

        val identityVerification = identityVerificationService.verify(
            IdentityVerificationRequest(
                userId = profile.userId,
                displayName = profile.displayName,
                birthDate = profile.birthDate
            )
        )

        if (identityVerification.verified && !profile.identityVerified) {
            profile.identityVerified = true
            profile.updatedAt = OffsetDateTime.now()
            return profileRepository.save(profile)
        }

        return profile
    }

    fun activateProfile(profileId: UUID): Profile {

        val profile = findByIdOrThrow(profileId)

        if (
            profile.status != ProfileStatus.DRAFT &&
            profile.status != ProfileStatus.INACTIVE
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_NOT_ACTIVATABLE,
                message = "Profile cannot be activated from status ${profile.status}"
            )
        }

        validatePhotosOrThrow(profileId)

        profile.status = ProfileStatus.ACTIVE
        profile.updatedAt = OffsetDateTime.now()

        return profileRepository.save(profile)
    }

    private fun validatePhotoCount(profileId: UUID, position: Int) {
        if (profilePhotoRepository.findByProfileIdAndPosition(profileId, position) != null) {
            throw DomainConflictException(
                code = DomainErrorCode.PHOTO_POSITION_OCCUPIED,
                message = "Photo position $position is already used"
            )
        }

        val currentCount = profilePhotoRepository.countByProfileId(profileId)

        if (currentCount >= maxPhotoCount) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_PHOTO_LIMIT_REACHED,
                message = "Profile already has the maximum number of photos ($maxPhotoCount)"
            )
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

    fun getPhotoResponses(profileId: UUID): List<PhotoResponse> {
        findByIdOrThrow(profileId)

        return profilePhotoRepository.findByProfileId(profileId)
            .sortedBy { it.position }
            .map { photo ->
                PhotoResponse.from(
                    photo = photo,
                    url = resolvePhotoReadUrl(photo)
                )
            }
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

    fun updateDynamicMatchFilters(
        profileId: UUID,
        preferredMinAge: Int,
        preferredMaxAge: Int,
        maxDistanceKm: Int
    ): Profile {
        val profile = findByIdOrThrow(profileId)

        validateDynamicMatchFilters(
            preferredMinAge = preferredMinAge,
            preferredMaxAge = preferredMaxAge,
            maxDistanceKm = maxDistanceKm
        )

        profile.preferredMinAge = preferredMinAge
        profile.preferredMaxAge = preferredMaxAge
        profile.maxDistanceKm = maxDistanceKm
        profile.updatedAt = OffsetDateTime.now()

        return profileRepository.save(profile)
    }

    fun deletePhoto(
        profileId: UUID,
        photoId: UUID
    ): Profile {

        val profile = findByIdOrThrow(profileId)

        val existing = profilePhotoRepository.findById(photoId)
            .orElseThrow {
                profilePhotoNotFound(photoId)
            }

        if (existing.profileId != profileId) {
            throw profilePhotoNotFound(photoId)
        }

        val storageKey = existing.storageKey

        profilePhotoRepository.delete(existing)

        runCatching {
            storageService.delete(storageKey)
        }

        moveActiveProfileToDraftAfterPhotoMutation(profile)

        profile.updatedAt = OffsetDateTime.now()

        return profileRepository.save(profile)
    }

    @Transactional
    fun replacePhoto(
        profileId: UUID,
        photoId: UUID,
        contentType: String?,
        bytes: ByteArray
    ): ProfilePhoto {

        val profile = findByIdOrThrow(profileId)

        val existingPhoto = profilePhotoRepository.findById(photoId)
            .orElseThrow {
                profilePhotoNotFound(photoId)
            }

        if (existingPhoto.profileId != profileId) {
            throw profilePhotoNotFound(photoId)
        }

        validatePhotoUpload(
            contentType = contentType,
            sizeBytes = bytes.size.toLong()
        )

        val normalizedContentType = contentType!!.lowercase()
        val newObjectPhotoId = UUID.randomUUID()
        val validation = profilePhotoValidationService.validateUploadedPhoto(
            contentType = normalizedContentType,
            bytes = bytes,
            replacingPhoto = existingPhoto
        )

        var newUploadedKey: String? = null
        val oldStorageKey = existingPhoto.storageKey

        try {
            val storedObject = storageService.uploadProfilePhoto(
                userId = profile.userId,
                photoId = newObjectPhotoId,
                contentType = normalizedContentType,
                bytes = bytes
            )

            newUploadedKey = storedObject.key

            existingPhoto.storageProvider = PhotoStorageProvider.S3
            existingPhoto.storageBucket = storedObject.bucket
            existingPhoto.storageKey = storedObject.key
            existingPhoto.isPersonPhoto = validation.isPersonPhoto
            existingPhoto.isFullBody = validation.isFullBody
            existingPhoto.validationStatus = validation.status

            val saved = profilePhotoRepository.save(existingPhoto)

            runCatching {
                storageService.delete(oldStorageKey)
            }

            moveActiveProfileToDraftAfterPhotoMutation(profile)

            return saved

        } catch (ex: Exception) {
            newUploadedKey?.let { key ->
                runCatching {
                    storageService.delete(key)
                }
            }

            throw ex
        }
    }

    @Transactional
    fun uploadPhoto(
        profileId: UUID,
        position: Int,
        contentType: String?,
        bytes: ByteArray
    ): ProfilePhoto {

        val profile = findByIdOrThrow(profileId)

        validatePhotoPosition(position)

        validatePhotoUpload(
            contentType = contentType,
            sizeBytes = bytes.size.toLong()
        )

        validatePhotoCount(profileId, position)

        val normalizedContentType = contentType!!.lowercase()
        val photoId = UUID.randomUUID()
        val validation = profilePhotoValidationService.validateUploadedPhoto(
            contentType = normalizedContentType,
            bytes = bytes,
            replacingPhoto = null
        )

        var uploadedKey: String? = null

        try {
            val storedObject = storageService.uploadProfilePhoto(
                userId = profile.userId,
                photoId = photoId,
                contentType = normalizedContentType,
                bytes = bytes
            )

            uploadedKey = storedObject.key

            val photo = profilePhotoRepository.save(
                ProfilePhoto(
                    id = photoId,
                    profileId = profileId,
                    storageProvider = PhotoStorageProvider.S3,
                    storageBucket = storedObject.bucket,
                    storageKey = storedObject.key,
                    position = position,
                    isPersonPhoto = validation.isPersonPhoto,
                    isFullBody = validation.isFullBody,
                    validationStatus = validation.status
                )
            )

            moveActiveProfileToDraftAfterPhotoMutation(profile)

            return photo

        } catch (ex: Exception) {
            uploadedKey?.let { key ->
                runCatching {
                    storageService.delete(key)
                }
            }

            throw ex
        }
    }

    fun resolvePhotoReadUrlForResponse(photo: ProfilePhoto): String {
        return resolvePhotoReadUrl(photo)
    }

    private fun moveActiveProfileToDraftAfterPhotoMutation(profile: Profile) {
        if (profile.status == ProfileStatus.ACTIVE) {
            profile.status = ProfileStatus.DRAFT
            profile.updatedAt = OffsetDateTime.now()
        }
    }

    private fun resolvePhotoReadUrl(photo: ProfilePhoto): String {
        return storageService.getReadUrl(photo.storageKey)
    }

    private fun validateDisplayName(displayName: String) {
        require(displayName.length in DISPLAY_NAME_MIN_LENGTH..DISPLAY_NAME_MAX_LENGTH) {
            "Display name must be between $DISPLAY_NAME_MIN_LENGTH and $DISPLAY_NAME_MAX_LENGTH characters"
        }

        PlainText.requireValid("Display name", displayName)
    }

    private fun validateBirthDate(birthDate: LocalDate) {
        if (!birthDate.isBefore(LocalDate.now())) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_BIRTH_DATE,
                message = "Birth date must be in the past"
            )
        }

        val age = Period.between(birthDate, LocalDate.now()).years

        if (age !in MIN_PROFILE_AGE..MAX_PROFILE_AGE) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_BIRTH_DATE,
                message = "Profile age must be between $MIN_PROFILE_AGE and $MAX_PROFILE_AGE"
            )
        }
    }

    private fun validateDynamicMatchFilters(
        preferredMinAge: Int,
        preferredMaxAge: Int,
        maxDistanceKm: Int
    ) {
        if (preferredMinAge !in MIN_PROFILE_AGE..MAX_PROFILE_AGE) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_MATCH_FILTERS,
                message = "Preferred minimum age must be between $MIN_PROFILE_AGE and $MAX_PROFILE_AGE"
            )
        }

        if (preferredMaxAge !in MIN_PROFILE_AGE..MAX_PROFILE_AGE) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_MATCH_FILTERS,
                message = "Preferred maximum age must be between $MIN_PROFILE_AGE and $MAX_PROFILE_AGE"
            )
        }

        if (preferredMinAge > preferredMaxAge) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_MATCH_FILTERS,
                message = "Preferred minimum age must be less than or equal to preferred maximum age"
            )
        }

        if (maxDistanceKm !in 1..1000) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_MATCH_FILTERS,
                message = "Maximum distance must be between 1 and 1000 kilometers"
            )
        }
    }

    private fun validatePhotoPosition(position: Int) {
        if (position !in 1..maxPhotoCount) {
            throw DomainBadRequestException(
                code = DomainErrorCode.PHOTO_POSITION_INVALID,
                message = "Photo position must be between 1 and $maxPhotoCount"
            )
        }
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

        PlainText.requireValid(fieldName, value)
    }

    private fun validatePhotoUpload(
        contentType: String?,
        sizeBytes: Long
    ) {
        if (contentType.isNullOrBlank()) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_PHOTO,
                message = "Photo content type is required"
            )
        }

        val normalizedContentType = contentType.lowercase()

        if (!profilePhotoStorageProperties.allowedContentTypes.contains(normalizedContentType)) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_PHOTO,
                message = "Unsupported photo content type: $contentType"
            )
        }

        if (sizeBytes <= 0) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_PHOTO,
                message = "Photo file is empty"
            )
        }

        if (sizeBytes > profilePhotoStorageProperties.maxSizeBytes) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_PHOTO,
                message = "Photo exceeds maximum size"
            )
        }
    }

    private fun validatePhotosOrThrow(profileId: UUID) {
        val photos = profilePhotoRepository.findByProfileId(profileId)
            .filter { it.validationStatus == PhotoValidationStatus.VALIDATED }

        val total = photos.size
        val personCount = photos.count { it.isPersonPhoto }
        val fullBodyCount = photos.count { it.isFullBody }

        if (total < requiredPhotoCount) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_PHOTOS_REQUIRED,
                message = "Profile must have at least $requiredPhotoCount validated photos"
            )
        }

        if (personCount < minPersonPhotos) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_PERSON_PHOTO_REQUIRED,
                message = "Profile must have at least $minPersonPhotos validated person photos"
            )
        }

        if (fullBodyCount < minFullBodyPhotos) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_FULL_BODY_PHOTO_REQUIRED,
                message = "Profile must have at least $minFullBodyPhotos validated full-body photos"
            )
        }
    }

    private fun normalizeOptionalText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }

    private fun profilePhotoNotFound(photoId: UUID): DomainNotFoundException =
        DomainNotFoundException(
            code = DomainErrorCode.PROFILE_PHOTO_NOT_FOUND,
            message = "Profile photo not found: $photoId"
        )
}
