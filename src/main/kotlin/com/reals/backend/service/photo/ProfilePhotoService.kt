package com.reals.backend.service.photo

import com.reals.backend.config.s3.ProfilePhotoValidationProperties
import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.StoredObject
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.AuditEventService
import com.reals.backend.service.HomeStateInvalidationService
import com.reals.backend.service.MediaCleanupProcessor
import com.reals.backend.service.MediaCleanupTaskService
import com.reals.backend.service.ProfilePhotoNormalizer
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.authenticity.ProfileAuthenticityPhotoCandidate
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

data class ProfilePhotoView(
    val photo: ProfilePhoto,
    val readUrl: String
)

@Service
@Transactional
class ProfilePhotoService(
    private val profileRepository: ProfileRepository,
    private val profilePhotoRepository: ProfilePhotoRepository,
    private val profilePhotoNormalizer: ProfilePhotoNormalizer,
    private val profilePhotoAnalysisService: ProfilePhotoAnalysisService,
    private val storageService: S3StorageService,
    private val mediaCleanupTaskService: MediaCleanupTaskService,
    private val mediaCleanupProcessor: MediaCleanupProcessor,
    private val transactionTemplate: TransactionTemplate,
    private val profilePhotoValidationProperties: ProfilePhotoValidationProperties,
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
    private val requireModerationApprovalForActivation: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getPhotos(profileId: UUID): List<ProfilePhoto> {
        findProfileByIdOrThrow(profileId)
        return profilePhotoRepository.findByProfileId(profileId)
            .sortedBy { it.position }
    }

    fun getPhotoViews(profileId: UUID): List<ProfilePhotoView> {
        findProfileByIdOrThrow(profileId)
        return profilePhotoRepository.findByProfileId(profileId)
            .sortedBy { it.position }
            .map { photo ->
                ProfilePhotoView(
                    photo = photo,
                    readUrl = resolvePhotoReadUrl(photo)
                )
            }
    }

    fun getExternallyVisiblePhotoViews(profileId: UUID): List<ProfilePhotoView> {
        findProfileByIdOrThrow(profileId)
        return profilePhotoRepository.findByProfileId(profileId)
            .asSequence()
            .filter { it.moderationStatus == PhotoModerationStatus.APPROVED }
            .sortedBy { it.position }
            .map { photo ->
                ProfilePhotoView(
                    photo = photo,
                    readUrl = resolvePhotoReadUrl(photo)
                )
            }
            .toList()
    }

    fun resolvePhotoReadUrl(photo: ProfilePhoto): String {
        return storageService.getReadUrl(
            bucket = requireNotNull(photo.storageBucket) {
                "profile photo storageBucket is required"
            },
            key = photo.storageKey
        )
    }

    fun authenticityCandidatesFor(profileId: UUID): List<ProfileAuthenticityPhotoCandidate> =
        profilePhotoRepository.findByProfileId(profileId)
            .filter {
                it.validationStatus == PhotoValidationStatus.VALIDATED &&
                    it.isPersonPhoto
            }
            .sortedBy { it.position }
            .map {
                ProfileAuthenticityPhotoCandidate(
                    photoId = it.id,
                    photoVersion = it.version,
                    storageKey = it.storageKey
                )
            }

    fun validatePhotosForActivation(profileId: UUID) {
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

    @Transactional
    fun reorderPhotos(
        profileId: UUID,
        placements: List<PhotoPlacement>
    ): List<ProfilePhoto> {
        val profile = findProfileByIdOrThrow(profileId)
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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun deletePhoto(
        profileId: UUID,
        photoId: UUID
    ): Profile {
        val result = transactionTemplate.execute {
            val profile = findProfileByIdOrThrow(profileId)

            val existing = profilePhotoRepository.findByIdForUpdate(photoId)
                ?: throw profilePhotoNotFound(photoId)

            if (existing.profileId != profileId) {
                throw profilePhotoNotFound(photoId)
            }

            val oldObject = storedObjectFor(existing)
            profilePhotoRepository.delete(existing)
            profilePhotoRepository.flush()
            val cleanupTask = mediaCleanupTaskService.createImmediateDeleteTaskInCurrentTransaction(oldObject)

            val authenticityOldStatus = profile.authenticityVerificationStatus
            val authenticityInvalidated = invalidateProfileAuthenticityAfterPhotoMutation(profile)
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
            if (authenticityInvalidated) {
                recordProfileAuthenticityVerificationUpdated(
                    profile = savedProfile,
                    oldStatus = authenticityOldStatus,
                    reason = "PROFILE_PHOTO_MUTATED"
                )
            }
            if (movedToDraft) {
                homeStateInvalidationService.bump(
                    userId = savedProfile.userId,
                    reason = "profile_moved_to_draft"
                )
            }
            PhotoDeleteResult(
                profile = savedProfile,
                cleanupTaskId = cleanupTask.id
            )
        }

        mediaCleanupProcessor.processTask(result.cleanupTaskId)
        return result.profile
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun replacePhoto(
        profileId: UUID,
        photoId: UUID,
        contentType: String?,
        bytes: ByteArray
    ): ProfilePhoto {
        val profile = findProfileByIdOrThrow(profileId)

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

        val newObjectPhotoId = UUID.randomUUID()
        val normalizedPhoto = profilePhotoNormalizer.normalize(
            contentType = contentType!!,
            bytes = bytes
        )
        val analysis = analyzeUploadedPhotoOrThrow(
            userId = profile.userId,
            profileId = profileId,
            photoId = newObjectPhotoId,
            contentType = normalizedPhoto.contentType,
            bytes = normalizedPhoto.bytes
        )
        val newStorageKey = storageService.profilePhotoObjectKey(
            userId = profile.userId,
            objectId = newObjectPhotoId,
            contentType = normalizedPhoto.contentType
        )
        val guardTask = mediaCleanupTaskService.createGuardTask(
            storageProvider = PhotoStorageProvider.S3,
            bucket = storageService.profilePhotoBucket(),
            objectKey = newStorageKey
        )

        var storedObject: StoredObject? = null
        val result = try {
            val uploadedObject = storageService.uploadProfilePhoto(
                userId = profile.userId,
                photoId = newObjectPhotoId,
                contentType = normalizedPhoto.contentType,
                bytes = normalizedPhoto.bytes
            )
            storedObject = uploadedObject

            transactionTemplate.execute {
                val authoritativeProfile = findProfileByIdOrThrow(profileId)
                val authoritativePhoto = profilePhotoRepository.findByIdForUpdate(photoId)
                    ?: throw profilePhotoNotFound(photoId)

                if (authoritativePhoto.profileId != profileId) {
                    throw profilePhotoNotFound(photoId)
                }

                val oldObject = storedObjectFor(authoritativePhoto)

                authoritativePhoto.storageProvider = PhotoStorageProvider.S3
                authoritativePhoto.storageBucket = uploadedObject.bucket
                authoritativePhoto.storageKey = uploadedObject.key
                authoritativePhoto.isPersonPhoto = analysis.validation.isPersonPhoto
                authoritativePhoto.isFullBody = analysis.validation.isFullBody
                authoritativePhoto.validationStatus = analysis.validation.status
                authoritativePhoto.moderationStatus = analysis.moderation.status

                val saved = profilePhotoRepository.saveAndFlush(authoritativePhoto)
                val oldCleanupTask = mediaCleanupTaskService.createImmediateDeleteTaskInCurrentTransaction(oldObject)
                mediaCleanupTaskService.deleteTaskInCurrentTransaction(guardTask.id)

                val authenticityOldStatus = authoritativeProfile.authenticityVerificationStatus
                val authenticityInvalidated = invalidateProfileAuthenticityAfterPhotoMutation(authoritativeProfile)
                val movedToDraft = moveActiveProfileToDraftAfterPhotoMutation(authoritativeProfile)
                authoritativeProfile.updatedAt = OffsetDateTime.now()
                profileRepository.save(authoritativeProfile)

                auditEventService.record(
                    eventType = AuditEventType.PROFILE_PHOTO_REPLACED,
                    aggregateType = AuditAggregateType.PROFILE_PHOTO,
                    aggregateId = saved.id,
                    actorUserId = authoritativeProfile.userId,
                    metadata = photoAuditMetadata(saved)
                )
                if (authenticityInvalidated) {
                    recordProfileAuthenticityVerificationUpdated(
                        profile = authoritativeProfile,
                        oldStatus = authenticityOldStatus,
                        reason = "PROFILE_PHOTO_MUTATED"
                    )
                }
                if (movedToDraft) {
                    homeStateInvalidationService.bump(
                        userId = authoritativeProfile.userId,
                        reason = "profile_moved_to_draft"
                    )
                }

                PhotoReplaceResult(
                    photo = saved,
                    oldCleanupTaskId = oldCleanupTask.id
                )
            }
        } catch (ex: Exception) {
            storedObject?.let {
                cleanupNewObjectAfterFailure(
                    storedObject = it,
                    guardTaskId = guardTask.id
                )
            }
            throw ex
        }

        try {
            mediaCleanupProcessor.processTask(result.oldCleanupTaskId)
        } catch (ex: Exception) {
            log.warn(
                "Immediate old profile-photo cleanup failed after replacement persistence; scheduled retry remains for task={}",
                result.oldCleanupTaskId,
                ex
            )
        }

        return result.photo
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun uploadPhoto(
        profileId: UUID,
        position: Int,
        contentType: String?,
        bytes: ByteArray
    ): ProfilePhoto {
        val profile = findProfileByIdOrThrow(profileId)

        validatePhotoPosition(position)

        validatePhotoUpload(
            contentType = contentType,
            sizeBytes = bytes.size.toLong()
        )

        validatePhotoCount(profileId, position)

        val photoId = UUID.randomUUID()
        val normalizedPhoto = profilePhotoNormalizer.normalize(
            contentType = contentType!!,
            bytes = bytes
        )
        val analysis = analyzeUploadedPhotoOrThrow(
            userId = profile.userId,
            profileId = profileId,
            photoId = photoId,
            contentType = normalizedPhoto.contentType,
            bytes = normalizedPhoto.bytes
        )
        val storageKey = storageService.profilePhotoObjectKey(
            userId = profile.userId,
            objectId = photoId,
            contentType = normalizedPhoto.contentType
        )
        val guardTask = mediaCleanupTaskService.createGuardTask(
            storageProvider = PhotoStorageProvider.S3,
            bucket = storageService.profilePhotoBucket(),
            objectKey = storageKey
        )

        var storedObject: StoredObject? = null
        try {
            val uploadedObject = storageService.uploadProfilePhoto(
                userId = profile.userId,
                photoId = photoId,
                contentType = normalizedPhoto.contentType,
                bytes = normalizedPhoto.bytes
            )
            storedObject = uploadedObject

            val photo = transactionTemplate.execute {
                val authoritativeProfile = findProfileByIdOrThrow(profileId)
                validatePhotoCount(profileId, position)

                val savedPhoto = profilePhotoRepository.saveAndFlush(
                    ProfilePhoto(
                        id = photoId,
                        profileId = profileId,
                        storageProvider = PhotoStorageProvider.S3,
                        storageBucket = uploadedObject.bucket,
                        storageKey = uploadedObject.key,
                        position = position,
                        isPersonPhoto = analysis.validation.isPersonPhoto,
                        isFullBody = analysis.validation.isFullBody,
                        validationStatus = analysis.validation.status,
                        moderationStatus = analysis.moderation.status
                    )
                )
                mediaCleanupTaskService.deleteTaskInCurrentTransaction(guardTask.id)

                val authenticityOldStatus = authoritativeProfile.authenticityVerificationStatus
                val authenticityInvalidated = invalidateProfileAuthenticityAfterPhotoMutation(authoritativeProfile)
                val movedToDraft = moveActiveProfileToDraftAfterPhotoMutation(authoritativeProfile)
                authoritativeProfile.updatedAt = OffsetDateTime.now()

                profileRepository.save(authoritativeProfile)

                auditEventService.record(
                    eventType = AuditEventType.PROFILE_PHOTO_UPLOADED,
                    aggregateType = AuditAggregateType.PROFILE_PHOTO,
                    aggregateId = savedPhoto.id,
                    actorUserId = authoritativeProfile.userId,
                    metadata = photoAuditMetadata(savedPhoto)
                )
                if (authenticityInvalidated) {
                    recordProfileAuthenticityVerificationUpdated(
                        profile = authoritativeProfile,
                        oldStatus = authenticityOldStatus,
                        reason = "PROFILE_PHOTO_MUTATED"
                    )
                }
                if (movedToDraft) {
                    homeStateInvalidationService.bump(
                        userId = authoritativeProfile.userId,
                        reason = "profile_moved_to_draft"
                    )
                }

                savedPhoto
            }

            return photo
        } catch (ex: Exception) {
            storedObject?.let {
                cleanupNewObjectAfterFailure(
                    storedObject = it,
                    guardTaskId = guardTask.id
                )
            }
            throw ex
        }
    }

    private fun findProfileByIdOrThrow(profileId: UUID): Profile =
        profileRepository.findById(profileId)
            .orElseThrow {
                NoSuchElementException("Profile not found: $profileId")
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

        if (!profilePhotoValidationProperties.allowedContentTypes.contains(normalizedContentType)) {
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

        if (sizeBytes > profilePhotoValidationProperties.maxFileSizeBytes) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_PHOTO,
                message = "Photo exceeds maximum size"
            )
        }
    }

    private fun analyzeUploadedPhotoOrThrow(
        userId: UUID,
        profileId: UUID,
        photoId: UUID,
        contentType: String,
        bytes: ByteArray
    ): ProfilePhotoAnalysisDecision {
        val result = profilePhotoAnalysisService.analyzeUploadedPhoto(
            userId = userId,
            profileId = profileId,
            photoId = photoId,
            contentType = contentType,
            bytes = bytes
        )

        if (result.moderation.status == PhotoModerationStatus.REJECTED && !persistRejectedPhotos) {
            // MVP default: rejected photos are not uploaded or persisted. A future
            // moderation workflow may retain rejected media for review/audit.
            throw DomainBadRequestException(
                code = DomainErrorCode.PROFILE_PHOTO_REJECTED,
                message = "Profile photo was rejected by moderation"
            )
        }

        return result
    }

    private fun storedObjectFor(photo: ProfilePhoto): StoredObject =
        StoredObject(
            bucket = photo.storageBucket ?: storageService.profilePhotoBucket(),
            key = photo.storageKey,
            contentType = "application/octet-stream",
            sizeBytes = 0
        )

    private fun cleanupNewObjectAfterFailure(
        storedObject: StoredObject,
        guardTaskId: UUID
    ) {
        try {
            storageService.deleteObject(
                bucket = storedObject.bucket,
                key = storedObject.key
            )
            mediaCleanupTaskService.completeTask(guardTaskId)
        } catch (cleanupFailure: Exception) {
            log.warn(
                "Failed to clean up newly uploaded profile-photo object after persistence failure; cleanup task={}",
                guardTaskId,
                cleanupFailure
            )
        }
    }

    private fun moveActiveProfileToDraftAfterPhotoMutation(profile: Profile): Boolean {
        if (profile.status == ProfileStatus.ACTIVE) {
            profile.status = ProfileStatus.DRAFT
            profile.updatedAt = OffsetDateTime.now()
            return true
        }
        return false
    }

    private fun invalidateProfileAuthenticityAfterPhotoMutation(profile: Profile): Boolean {
        val oldStatus = profile.authenticityVerificationStatus
        val newStatus = when (oldStatus) {
            ProfileAuthenticityVerificationStatus.NOT_STARTED -> ProfileAuthenticityVerificationStatus.NOT_STARTED
            ProfileAuthenticityVerificationStatus.STALE -> ProfileAuthenticityVerificationStatus.STALE
            ProfileAuthenticityVerificationStatus.PENDING,
            ProfileAuthenticityVerificationStatus.VERIFIED,
            ProfileAuthenticityVerificationStatus.REJECTED,
            ProfileAuthenticityVerificationStatus.NEEDS_REVIEW -> ProfileAuthenticityVerificationStatus.STALE
        }

        profile.authenticityVerificationStatus = newStatus
        profile.authenticityVerified = newStatus == ProfileAuthenticityVerificationStatus.VERIFIED
        if (oldStatus != newStatus) {
            profile.updatedAt = OffsetDateTime.now()
        }

        return oldStatus != ProfileAuthenticityVerificationStatus.STALE &&
            newStatus == ProfileAuthenticityVerificationStatus.STALE
    }

    private fun recordProfileAuthenticityVerificationUpdated(
        profile: Profile,
        oldStatus: ProfileAuthenticityVerificationStatus,
        reason: String? = null
    ) {
        auditEventService.record(
            eventType = AuditEventType.PROFILE_AUTHENTICITY_VERIFICATION_UPDATED,
            aggregateType = AuditAggregateType.PROFILE,
            aggregateId = profile.id,
            actorUserId = profile.userId,
            metadata = mapOf(
                "oldStatus" to oldStatus.name,
                "newStatus" to profile.authenticityVerificationStatus.name,
                "authenticityVerified" to profile.authenticityVerified,
                "reason" to reason
            )
        )
    }

    private fun photoAuditMetadata(photo: ProfilePhoto): Map<String, Any?> =
        mapOf(
            "profileId" to photo.profileId,
            "position" to photo.position,
            "validationStatus" to photo.validationStatus.name,
            "moderationStatus" to photo.moderationStatus.name
        )

    private fun profilePhotoNotFound(photoId: UUID): DomainNotFoundException =
        DomainNotFoundException(
            code = DomainErrorCode.PROFILE_PHOTO_NOT_FOUND,
            message = "Profile photo not found: $photoId"
        )

    private data class PhotoDeleteResult(
        val profile: Profile,
        val cleanupTaskId: UUID
    )

    private data class PhotoReplaceResult(
        val photo: ProfilePhoto,
        val oldCleanupTaskId: UUID
    )

    private companion object {
        const val MIN_PHOTO_POSITION = 1
        const val MAX_PHOTO_POSITION = 9
        const val TEMPORARY_PHOTO_POSITION_OFFSET = 1000
    }
}
