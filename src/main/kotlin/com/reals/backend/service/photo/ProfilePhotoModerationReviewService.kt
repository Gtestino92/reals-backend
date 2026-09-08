package com.reals.backend.service.photo

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.AuditEventService
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.UUID

enum class AdminPhotoModerationDecision {
    APPROVED,
    REJECTED
}

data class AdminProfilePhotoModerationReview(
    val photo: ProfilePhoto,
    val profile: Profile,
    val readUrl: String
)

@Service
@Transactional
class ProfilePhotoModerationReviewService(
    private val profilePhotoRepository: ProfilePhotoRepository,
    private val profileRepository: ProfileRepository,
    private val storageService: S3StorageService,
    private val auditEventService: AuditEventService
) {

    fun listNeedsReview(): List<AdminProfilePhotoModerationReview> {
        val photos = profilePhotoRepository.findTop100ByModerationStatusOrderByCreatedAtAsc(
            PhotoModerationStatus.NEEDS_REVIEW
        )
        if (photos.isEmpty()) {
            return emptyList()
        }

        val profilesById = profileRepository.findAllById(
            photos.map { it.profileId }.distinct()
        ).associateBy { it.id }

        return photos.map { photo ->
            val profile = profilesById[photo.profileId]
                ?: throw DomainNotFoundException(
                    code = DomainErrorCode.PROFILE_NOT_FOUND,
                    message = "Profile not found for profile photo"
                )

            reviewFor(photo = photo, profile = profile)
        }
    }

    fun resolve(
        photoId: UUID,
        adminUserId: UUID,
        expectedPhotoVersion: Long,
        decision: AdminPhotoModerationDecision,
        notes: String?
    ): AdminProfilePhotoModerationReview {
        val photo = profilePhotoRepository.findByIdForUpdate(photoId)
            ?: throw DomainNotFoundException(
                code = DomainErrorCode.PROFILE_PHOTO_NOT_FOUND,
                message = "Profile photo not found"
            )

        if (photo.version != expectedPhotoVersion) {
            throw reviewNotAvailable()
        }

        if (photo.moderationStatus != PhotoModerationStatus.NEEDS_REVIEW) {
            throw reviewNotAvailable()
        }

        val profile = profileRepository.findById(photo.profileId).orElseThrow {
            DomainNotFoundException(
                code = DomainErrorCode.PROFILE_NOT_FOUND,
                message = "Profile not found for profile photo"
            )
        }
        val previousStatus = photo.moderationStatus
        val newStatus = decision.toModerationStatus()

        photo.moderationStatus = newStatus
        val saved = profilePhotoRepository.save(photo)

        auditEventService.record(
            eventType = AuditEventType.PHOTO_MODERATION_UPDATED,
            aggregateType = AuditAggregateType.PROFILE_PHOTO,
            aggregateId = saved.id,
            actorUserId = adminUserId,
            targetUserId = profile.userId,
            metadata = mapOf(
                "profileId" to profile.id,
                "position" to saved.position,
                "previousModerationStatus" to previousStatus.name,
                "newModerationStatus" to newStatus.name,
                "decision" to decision.name,
                "source" to "ADMIN_REVIEW",
                "notes" to notes?.trim()?.ifBlank { null },
                "validationStatus" to saved.validationStatus.name,
                "isPersonPhoto" to saved.isPersonPhoto,
                "isFullBody" to saved.isFullBody
            )
        )

        return reviewFor(photo = saved, profile = profile)
    }

    private fun reviewFor(
        photo: ProfilePhoto,
        profile: Profile
    ): AdminProfilePhotoModerationReview =
        AdminProfilePhotoModerationReview(
            photo = photo,
            profile = profile,
            readUrl = storageService.getReadUrl(
                bucket = requireNotNull(photo.storageBucket) {
                    "profile photo storageBucket is required"
                },
                key = photo.storageKey
            )
        )

    private fun AdminPhotoModerationDecision.toModerationStatus(): PhotoModerationStatus =
        when (this) {
            AdminPhotoModerationDecision.APPROVED -> PhotoModerationStatus.APPROVED
            AdminPhotoModerationDecision.REJECTED -> PhotoModerationStatus.REJECTED
        }

    private fun reviewNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.PROFILE_PHOTO_MODERATION_REVIEW_NOT_AVAILABLE,
            message = "Profile photo is not awaiting moderation review"
        )
}
