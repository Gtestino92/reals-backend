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
import com.reals.backend.service.photo.PhotoModerationResult
import com.reals.backend.service.photo.ProfilePhotoModerationService
import com.reals.backend.validation.PlainText
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.util.*

data class PhotoPlacement(
    val photoId: UUID,
    val position: Int
)

@Service
@Transactional
class ProfileService(
    private val profileRepository: ProfileRepository,
    private val profilePhotoRepository: ProfilePhotoRepository,
    private val profilePhotoValidationService: ProfilePhotoValidationService,
    private val profilePhotoModerationService: ProfilePhotoModerationService,
    private val identityVerificationService: IdentityVerificationService,
    private val storageService: S3StorageService,
    private val profilePhotoStorageProperties: ProfilePhotoStorageProperties,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,

    @param:Value("\${profile.photos.max-count}")
    private val maxPhotoCount: Int,

    @param:Value("\${profile.photos.required-count}")
    private val requiredPhotoCount: Int,

    @param:Value("\${profile.photos.min-person-photos}")
    private val minPersonPhotos: Int,

    @param:Value("\${profile.photos.min-full-body-photos}")
    private val minFullBodyPhotos: Int,

    @param:Value("\${profile.photos.moderation.persist-rejected-photos:false}")
    private val persistRejectedPhotos: Boolean,

    @param:Value("\${profile.photos.require-moderation-approval-for-activation:false}")
    private val requireModerationApprovalForActivation: Boolean,

    @param:Value("\${profile.identity-verification.require-for-activation:false}")
    private val requireIdentityVerificationForActivation: Boolean
) {

    private companion object {
        const val DISPLAY_NAME_MIN_LENGTH = 2
        const val DISPLAY_NAME_MAX_LENGTH = 100
        const val LOCATION_MAX_LENGTH = 100
        const val BIO_MAX_LENGTH = 1000
        const val MIN_PROFILE_AGE = 18
        const val MAX_PROFILE_AGE = 99
        const val MIN_PHOTO_POSITION = 1
        const val MAX_PHOTO_POSITION = 9
        const val TEMPORARY_PHOTO_POSITION_OFFSET = 1000
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

        val saved = profileRepository.save(profile)
        homeStateInvalidationService.bump(
            userId = saved.userId,
            reason = "profile_created"
        )
        return saved
    }

    fun verifyIdentity(profileId: UUID): Profile {
        val profile = findByIdOrThrow(profileId)
        val oldStatus = profile.identityVerificationStatus

        val identityVerification = identityVerificationService.verify(
            IdentityVerificationRequest(
                userId = profile.userId,
                profileId = profile.id,
                displayName = profile.displayName,
                birthDate = profile.birthDate
            )
        )

        profile.identityVerificationStatus = identityVerification.status
        profile.identityVerified = identityVerification.status == IdentityVerificationStatus.VERIFIED
        profile.updatedAt = OffsetDateTime.now()

        val saved = profileRepository.save(profile)
        auditEventService.record(
            eventType = AuditEventType.IDENTITY_VERIFICATION_UPDATED,
            aggregateType = AuditAggregateType.PROFILE,
            aggregateId = saved.id,
            actorUserId = saved.userId,
            metadata = mapOf(
                "oldStatus" to oldStatus.name,
                "newStatus" to saved.identityVerificationStatus.name,
                "identityVerified" to saved.identityVerified
            )
        )
        return saved
    }

    fun activateProfile(profileId: UUID): Profile {

        val profile = findByIdOrThrow(profileId)
        val previousStatus = profile.status

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
        validateIdentityVerificationForActivation(profile)

        profile.status = ProfileStatus.ACTIVE
        profile.updatedAt = OffsetDateTime.now()

        val saved = profileRepository.save(profile)
        auditEventService.record(
            eventType = AuditEventType.PROFILE_ACTIVATED,
            aggregateType = AuditAggregateType.PROFILE,
            aggregateId = saved.id,
            actorUserId = saved.userId,
            metadata = mapOf(
                "previousStatus" to previousStatus.name,
                "newStatus" to saved.status.name
            )
        )
        homeStateInvalidationService.bump(
            userId = saved.userId,
            reason = "profile_activated"
        )
        return saved
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

    @Transactional
    fun reorderPhotos(
        profileId: UUID,
        placements: List<PhotoPlacement>
    ): List<ProfilePhoto> {
        val profile = findByIdOrThrow(profileId)
        validatePhotoPlacementsBasicShape(placements)

        val photos = profilePhotoRepository.findByProfileId(profileId)
        val currentPhotoIds = photos.map { it.id }.toSet()
        val requestedPhotoIds = placements.map { it.photoId }.toSet()

        if (!currentPhotoIds.containsAll(requestedPhotoIds)) {
            val missingForCurrentProfile = requestedPhotoIds.first { it !in currentPhotoIds }
            throw profilePhotoNotFound(missingForCurrentProfile)
        }

        if (requestedPhotoIds != currentPhotoIds) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_PHOTO,
                message = "Reorder request must include every current profile photo exactly once"
            )
        }

        val requestedPositionsByPhotoId = placements.associate { it.photoId to it.position }
        val previousPositionsByPhotoId = photos.associate { it.id.toString() to it.position }
        val newPositionsByPhotoId = placements.associate { it.photoId.toString() to it.position }

        try {
            // Reordering may swap positions that are unique within a profile.
            // Move all affected photos to temporary out-of-grid positions first so the
            // database never observes two rows with the same final position during the swap.
            photos.forEachIndexed { index, photo ->
                photo.position = TEMPORARY_PHOTO_POSITION_OFFSET + index
            }
            profilePhotoRepository.saveAll(photos)
            profilePhotoRepository.flush()

            photos.forEach { photo ->
                photo.position = requestedPositionsByPhotoId.getValue(photo.id)
            }
            val saved = profilePhotoRepository.saveAll(photos)
            profilePhotoRepository.flush()

            profile.updatedAt = OffsetDateTime.now()
            profileRepository.save(profile)

            auditEventService.record(
                eventType = AuditEventType.PROFILE_PHOTOS_REORDERED,
                aggregateType = AuditAggregateType.PROFILE,
                aggregateId = profile.id,
                actorUserId = profile.userId,
                metadata = mapOf(
                    "profileId" to profile.id,
                    "previousPositions" to previousPositionsByPhotoId,
                    "newPositions" to newPositionsByPhotoId
                )
            )

            return saved.sortedBy { it.position }
        } catch (ex: DataIntegrityViolationException) {
            throw DomainConflictException(
                code = DomainErrorCode.PHOTO_POSITION_OCCUPIED,
                message = "Photo positions could not be reordered because of a position conflict"
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

        val movedToDraft = moveActiveProfileToDraftAfterPhotoMutation(profile)

        profile.updatedAt = OffsetDateTime.now()

        val savedProfile = profileRepository.save(profile)
        auditEventService.record(
            eventType = AuditEventType.PROFILE_PHOTO_DELETED,
            aggregateType = AuditAggregateType.PROFILE_PHOTO,
            aggregateId = existing.id,
            actorUserId = profile.userId,
            metadata = mapOf(
                "profileId" to profile.id,
                "position" to existing.position,
                "validationStatus" to existing.validationStatus.name,
                "moderationStatus" to existing.moderationStatus.name
            )
        )
        if (movedToDraft) {
            homeStateInvalidationService.bump(
                userId = savedProfile.userId,
                reason = "profile_moved_to_draft"
            )
        }
        return savedProfile
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
        val moderation = moderateUploadedPhotoOrThrow(
            userId = profile.userId,
            profileId = profileId,
            photoId = newObjectPhotoId,
            contentType = normalizedContentType,
            bytes = bytes
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
            existingPhoto.moderationStatus = moderation.status

            val saved = profilePhotoRepository.save(existingPhoto)

            runCatching {
                storageService.delete(oldStorageKey)
            }

            val movedToDraft = moveActiveProfileToDraftAfterPhotoMutation(profile)

            auditEventService.record(
                eventType = AuditEventType.PROFILE_PHOTO_REPLACED,
                aggregateType = AuditAggregateType.PROFILE_PHOTO,
                aggregateId = saved.id,
                actorUserId = profile.userId,
                metadata = photoAuditMetadata(saved)
            )
            if (movedToDraft) {
                homeStateInvalidationService.bump(
                    userId = profile.userId,
                    reason = "profile_moved_to_draft"
                )
            }

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
        val moderation = moderateUploadedPhotoOrThrow(
            userId = profile.userId,
            profileId = profileId,
            photoId = photoId,
            contentType = normalizedContentType,
            bytes = bytes
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
                    validationStatus = validation.status,
                    moderationStatus = moderation.status
                )
            )

            val movedToDraft = moveActiveProfileToDraftAfterPhotoMutation(profile)

            auditEventService.record(
                eventType = AuditEventType.PROFILE_PHOTO_UPLOADED,
                aggregateType = AuditAggregateType.PROFILE_PHOTO,
                aggregateId = photo.id,
                actorUserId = profile.userId,
                metadata = photoAuditMetadata(photo)
            )
            if (movedToDraft) {
                homeStateInvalidationService.bump(
                    userId = profile.userId,
                    reason = "profile_moved_to_draft"
                )
            }

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

    private fun moveActiveProfileToDraftAfterPhotoMutation(profile: Profile): Boolean {
        if (profile.status == ProfileStatus.ACTIVE) {
            profile.status = ProfileStatus.DRAFT
            profile.updatedAt = OffsetDateTime.now()
            return true
        }
        return false
    }

    private fun resolvePhotoReadUrl(photo: ProfilePhoto): String {
        return storageService.getReadUrl(photo.storageKey)
    }

    private fun photoAuditMetadata(photo: ProfilePhoto): Map<String, Any?> =
        mapOf(
            "profileId" to photo.profileId,
            "position" to photo.position,
            "validationStatus" to photo.validationStatus.name,
            "moderationStatus" to photo.moderationStatus.name
        )

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
        if (position !in MIN_PHOTO_POSITION..maxPhotoCount) {
            throw DomainBadRequestException(
                code = DomainErrorCode.PHOTO_POSITION_INVALID,
                message = "Photo position must be between 1 and $maxPhotoCount"
            )
        }
    }

    private fun validatePhotoPlacementsBasicShape(placements: List<PhotoPlacement>) {
        if (placements.isEmpty() || placements.size > MAX_PHOTO_POSITION) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_PHOTO,
                message = "Reorder request must include between 1 and $MAX_PHOTO_POSITION photo placements"
            )
        }

        val invalidPosition = placements.firstOrNull {
            it.position !in MIN_PHOTO_POSITION..MAX_PHOTO_POSITION
        }
        if (invalidPosition != null) {
            throw DomainBadRequestException(
                code = DomainErrorCode.PHOTO_POSITION_INVALID,
                message = "Photo position must be between $MIN_PHOTO_POSITION and $MAX_PHOTO_POSITION"
            )
        }

        if (placements.map { it.position }.toSet().size != placements.size) {
            throw DomainBadRequestException(
                code = DomainErrorCode.PHOTO_POSITION_OCCUPIED,
                message = "Each profile photo must receive a unique final position"
            )
        }

        if (placements.map { it.photoId }.toSet().size != placements.size) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_PHOTO,
                message = "Each profile photo can only appear once in a reorder request"
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

        if (
            requireModerationApprovalForActivation &&
            photos.any { it.moderationStatus != PhotoModerationStatus.APPROVED }
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_PHOTO_MODERATION_NOT_APPROVED,
                message = "All profile photos must be approved by moderation before activation"
            )
        }
    }

    private fun validateIdentityVerificationForActivation(profile: Profile) {
        if (
            requireIdentityVerificationForActivation &&
            profile.identityVerificationStatus != IdentityVerificationStatus.VERIFIED
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_IDENTITY_VERIFICATION_REQUIRED,
                message = "Profile identity must be verified before activation"
            )
        }
    }

    private fun moderateUploadedPhotoOrThrow(
        userId: UUID,
        profileId: UUID,
        photoId: UUID,
        contentType: String,
        bytes: ByteArray
    ): PhotoModerationResult {
        val result = profilePhotoModerationService.moderateUploadedPhoto(
            userId = userId,
            profileId = profileId,
            photoId = photoId,
            contentType = contentType,
            bytes = bytes
        )

        if (result.status == PhotoModerationStatus.REJECTED && !persistRejectedPhotos) {
            // MVP default: rejected photos are not uploaded or persisted. A future
            // moderation workflow may retain rejected media for review/audit.
            throw DomainBadRequestException(
                code = DomainErrorCode.PROFILE_PHOTO_REJECTED,
                message = "Profile photo was rejected by moderation"
            )
        }

        return result
    }

    private fun normalizeOptionalText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }

    private fun profilePhotoNotFound(photoId: UUID): DomainNotFoundException =
        DomainNotFoundException(
            code = DomainErrorCode.PROFILE_PHOTO_NOT_FOUND,
            message = "Profile photo not found: $photoId"
        )
}
