package com.reals.backend.integration.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.S3StorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SafetyAuditSideEffectsIntegrationTest : BaseIT() {

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @Test
    fun `profile authenticity verification records audit event`() {
        val userId = createActiveProfile(
            email = "identity-audit-${UUID.randomUUID()}@example.com",
            displayName = "Identity Audit",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val profile = profileService.findByUserId(userId)!!

        profileService.verifyProfileAuthenticity(profile.id)

        val event = auditEventRepository.findAll()
            .single {
                it.eventType == AuditEventType.PROFILE_AUTHENTICITY_VERIFICATION_UPDATED &&
                    it.aggregateType == AuditAggregateType.PROFILE &&
                    it.aggregateId == profile.id
            }
        assertEquals(userId, event.actorUserId)
        assertTrue(event.metadataJson!!.contains("oldStatus"))
        assertTrue(event.metadataJson!!.contains("VERIFIED"))
        assertTrue(event.metadataJson!!.contains("authenticityVerified"))
    }

    @Test
    fun `profile activation records audit event`() {
        val user = userService.createUser("profile-activation-audit-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Activation Audit",
            birthDate = java.time.LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = com.reals.backend.domain.Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )
        repeat(4) { index ->
            profilePhotoRepository.save(
                com.reals.backend.domain.ProfilePhoto(
                    profileId = profile.id,
                    storageProvider = com.reals.backend.domain.PhotoStorageProvider.S3,
                    storageBucket = "reals-media-test",
                    storageKey = "audit/profile/${profile.id}/${index + 1}.jpg",
                    position = index + 1,
                    isPersonPhoto = index == 0,
                    isFullBody = index == 0,
                    validationStatus = com.reals.backend.domain.PhotoValidationStatus.VALIDATED,
                    moderationStatus = com.reals.backend.domain.PhotoModerationStatus.APPROVED
                )
            )
        }

        val activated = profileService.activateProfile(profile.id)

        assertEquals(ProfileStatus.ACTIVE, activated.status)
        val event = auditEventRepository.findAll()
            .single {
                it.eventType == AuditEventType.PROFILE_ACTIVATED &&
                    it.aggregateType == AuditAggregateType.PROFILE &&
                    it.aggregateId == profile.id
            }
        assertEquals(user.id, event.actorUserId)
        assertTrue(event.metadataJson!!.contains("DRAFT"))
        assertTrue(event.metadataJson!!.contains("ACTIVE"))
    }

    @Test
    fun `photo deletion records audit event`() {
        val userId = createActiveProfile(
            email = "photo-delete-audit-${UUID.randomUUID()}@example.com",
            displayName = "Photo Delete Audit",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val profile = profileService.findByUserId(userId)!!
        val photo = profilePhotoRepository.findByProfileId(profile.id).first()

        profilePhotoService.deletePhoto(profile.id, photo.id)

        val event = auditEventRepository.findAll()
            .single {
                it.eventType == AuditEventType.PROFILE_PHOTO_DELETED &&
                    it.aggregateType == AuditAggregateType.PROFILE_PHOTO &&
                    it.aggregateId == photo.id
            }
        assertEquals(userId, event.actorUserId)
        assertTrue(event.metadataJson!!.contains("validationStatus"))
        assertTrue(event.metadataJson!!.contains("moderationStatus"))
        assertTrue(!event.metadataJson!!.contains(photo.storageKey))
    }

    @Test
    fun `account deletion and reactivation record audit events`() {
        val userId = createActiveProfile(
            email = "account-audit-${UUID.randomUUID()}@example.com",
            displayName = "Account Audit",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        userService.deleteUser(userId)
        userService.reactivateUser(userId)

        assertEquals(
            1,
            auditEventRepository.findAll()
                .count {
                    it.eventType == AuditEventType.ACCOUNT_DELETION_REQUESTED &&
                        it.aggregateType == AuditAggregateType.USER &&
                        it.aggregateId == userId &&
                        it.actorUserId == userId
                }
        )
        assertEquals(
            1,
            auditEventRepository.findAll()
                .count {
                    it.eventType == AuditEventType.ACCOUNT_REACTIVATED &&
                        it.aggregateType == AuditAggregateType.USER &&
                        it.aggregateId == userId &&
                        it.actorUserId == userId
                }
        )
    }

    @Test
    fun `penalty creation records audit event`() {
        val userId = createActiveProfile(
            email = "penalty-audit-${UUID.randomUUID()}@example.com",
            displayName = "Penalty Audit",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        val penalty = penaltyService.createTemporaryPenalty(
            userId = userId,
            reason = "Temporary penalty audit",
            duration = Duration.ofHours(1)
        )

        val event = auditEventRepository.findAll()
            .single {
                it.eventType == AuditEventType.PENALTY_APPLIED &&
                    it.aggregateType == AuditAggregateType.PENALTY &&
                    it.aggregateId == penalty.id
            }
        assertEquals(userId, event.targetUserId)
        assertTrue(event.metadataJson!!.contains("TEMPORARY_BAN"))
        assertTrue(event.metadataJson!!.contains("expiresAtPresent"))
        assertTrue(!event.metadataJson!!.contains("Temporary penalty audit"))
    }
}
