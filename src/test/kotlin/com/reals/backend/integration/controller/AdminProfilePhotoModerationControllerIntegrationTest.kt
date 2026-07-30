package com.reals.backend.integration.controller

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.S3StorageService
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class AdminProfilePhotoModerationControllerIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `review queue only returns needs review photos without storage or user secrets`() {
        stubReadUrls()
        val fixture = createDraftProfile("review-filter")
        val pending = savePhoto(fixture.profileId, 1, PhotoModerationStatus.PENDING)
        val approved = savePhoto(fixture.profileId, 2, PhotoModerationStatus.APPROVED)
        val rejected = savePhoto(fixture.profileId, 3, PhotoModerationStatus.REJECTED)
        val needsReview = savePhoto(
            profileId = fixture.profileId,
            position = 4,
            moderationStatus = PhotoModerationStatus.NEEDS_REVIEW,
            validationStatus = PhotoValidationStatus.VALIDATED,
            isPersonPhoto = true,
            isFullBody = false
        )
        val admin = userService.createUser("admin-photo-review-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/admin/profile-photos/review")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()", equalTo(1)))
            .andExpect(jsonPath("$[0].photoId", equalTo(needsReview.id.toString())))
            .andExpect(jsonPath("$[0].profileId", equalTo(fixture.profileId.toString())))
            .andExpect(jsonPath("$[0].userId", equalTo(fixture.userId.toString())))
            .andExpect(jsonPath("$[0].displayName", equalTo("Review Filter")))
            .andExpect(jsonPath("$[0].position", equalTo(4)))
            .andExpect(jsonPath("$[0].readUrl", equalTo(readUrl(needsReview.storageBucket!!, needsReview.storageKey))))
            .andExpect(jsonPath("$[0].photoVersion", equalTo(needsReview.version.toInt())))
            .andExpect(jsonPath("$[0].validationStatus", equalTo("VALIDATED")))
            .andExpect(jsonPath("$[0].moderationStatus", equalTo("NEEDS_REVIEW")))
            .andExpect(jsonPath("$[0].isPersonPhoto", equalTo(true)))
            .andExpect(jsonPath("$[0].isFullBody", equalTo(false)))
            .andExpect(jsonPath("$[0].createdAt").exists())
            .andExpect(jsonPath("$[0]", not(hasKey("storageKey"))))
            .andExpect(jsonPath("$[0]", not(hasKey("storageBucket"))))
            .andExpect(jsonPath("$[0]", not(hasKey("email"))))
            .andExpect(jsonPath("$[0]", not(hasKey("firebaseUid"))))

        val returnedIds = listOf(pending.id, approved.id, rejected.id).map { it.toString() }
        returnedIds.forEach { id ->
            mockMvc.perform(
                get("/api/admin/profile-photos/review")
                    .with(authenticatedAsAdmin(admin.id))
            )
                .andExpect(jsonPath("$[?(@.photoId == '$id')]").isEmpty())
        }
    }

    @Test
    fun `review queue is ordered by oldest created photo first`() {
        stubReadUrls()
        val fixture = createDraftProfile("review-order")
        val newest = savePhoto(
            fixture.profileId,
            1,
            PhotoModerationStatus.NEEDS_REVIEW,
            createdAt = OffsetDateTime.parse("2026-07-09T12:00:03Z")
        )
        val oldest = savePhoto(
            fixture.profileId,
            2,
            PhotoModerationStatus.NEEDS_REVIEW,
            createdAt = OffsetDateTime.parse("2026-07-09T12:00:01Z")
        )
        val middle = savePhoto(
            fixture.profileId,
            3,
            PhotoModerationStatus.NEEDS_REVIEW,
            createdAt = OffsetDateTime.parse("2026-07-09T12:00:02Z")
        )
        val admin = userService.createUser("admin-photo-order-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/admin/profile-photos/review")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].photoId", equalTo(oldest.id.toString())))
            .andExpect(jsonPath("$[1].photoId", equalTo(middle.id.toString())))
            .andExpect(jsonPath("$[2].photoId", equalTo(newest.id.toString())))
    }

    @Test
    fun `empty review queue returns empty list`() {
        val admin = userService.createUser("admin-photo-empty-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/admin/profile-photos/review")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty())
    }

    @Test
    fun `review queue and moderation resolution require admin role`() {
        val user = userService.createUser("photo-review-user-${UUID.randomUUID()}@example.com")
        val fixture = createDraftProfile("review-security")
        val photo = savePhoto(fixture.profileId, 1, PhotoModerationStatus.NEEDS_REVIEW)

        mockMvc.perform(
            get("/api/admin/profile-photos/review")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/admin/profile-photos/${photo.id}/moderation")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content("""{"expectedPhotoVersion":${photo.version},"decision":"APPROVED"}""")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin can approve review without changing unrelated photo fields`() {
        stubReadUrls()
        val fixture = createDraftProfile("approve-review")
        val photo = savePhoto(
            profileId = fixture.profileId,
            position = 3,
            moderationStatus = PhotoModerationStatus.NEEDS_REVIEW,
            validationStatus = PhotoValidationStatus.PENDING,
            isPersonPhoto = false,
            isFullBody = false,
            storageKey = "users/${fixture.userId}/profile-photos/approve.jpg"
        )
        val admin = userService.createUser("admin-photo-approve-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/admin/profile-photos/${photo.id}/moderation")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content("""{"expectedPhotoVersion":${photo.version},"decision":"APPROVED","notes":"Reviewed manually"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.photoId", equalTo(photo.id.toString())))
            .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))

        val updated = profilePhotoRepository.findById(photo.id).orElseThrow()
        assertEquals(PhotoModerationStatus.APPROVED, updated.moderationStatus)
        assertEquals(PhotoValidationStatus.PENDING, updated.validationStatus)
        assertEquals(false, updated.isPersonPhoto)
        assertEquals(false, updated.isFullBody)
        assertEquals(photo.storageKey, updated.storageKey)
        assertEquals(photo.position, updated.position)

        val audit = singlePhotoModerationAudit(photo.id)
        assertEquals(AuditEventType.PHOTO_MODERATION_UPDATED, audit.eventType)
        assertEquals(AuditAggregateType.PROFILE_PHOTO, audit.aggregateType)
        assertEquals(photo.id, audit.aggregateId)
        assertEquals(admin.id, audit.actorUserId)
        assertEquals(fixture.userId, audit.targetUserId)
        assertTrue(audit.metadataJson!!.contains(fixture.profileId.toString()))
        assertTrue(audit.metadataJson!!.contains("\"position\":3"))
        assertTrue(audit.metadataJson!!.contains("NEEDS_REVIEW"))
        assertTrue(audit.metadataJson!!.contains("APPROVED"))
        assertTrue(audit.metadataJson!!.contains("ADMIN_REVIEW"))
        assertTrue(audit.metadataJson!!.contains("Reviewed manually"))
        assertTrue(audit.metadataJson!!.contains("PENDING"))
        assertTrue(audit.metadataJson!!.contains("isPersonPhoto"))
        assertTrue(audit.metadataJson!!.contains("isFullBody"))
        assertFalse(audit.metadataJson!!.contains(photo.storageKey))
        assertFalse(audit.metadataJson!!.contains("profile-photos"))
    }

    @Test
    fun `admin can reject review without deleting photo or creating penalty safety report or block`() {
        stubReadUrls()
        val fixture = createDraftProfile("reject-review")
        val photo = savePhoto(
            profileId = fixture.profileId,
            position = 2,
            moderationStatus = PhotoModerationStatus.NEEDS_REVIEW,
            validationStatus = PhotoValidationStatus.VALIDATED,
            isPersonPhoto = true,
            isFullBody = true,
            storageKey = "users/${fixture.userId}/profile-photos/reject.jpg"
        )
        val admin = userService.createUser("admin-photo-reject-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/admin/profile-photos/${photo.id}/moderation")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content("""{"expectedPhotoVersion":${photo.version},"decision":"REJECTED","notes":"Explicit content"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.moderationStatus", equalTo("REJECTED")))

        val updated = profilePhotoRepository.findById(photo.id).orElseThrow()
        assertEquals(PhotoModerationStatus.REJECTED, updated.moderationStatus)
        assertEquals(PhotoValidationStatus.VALIDATED, updated.validationStatus)
        assertEquals(true, updated.isPersonPhoto)
        assertEquals(true, updated.isFullBody)
        assertEquals(photo.storageKey, updated.storageKey)
        assertEquals(photo.position, updated.position)
        assertEquals(1L, profilePhotoRepository.countByProfileId(fixture.profileId))
        assertEquals(0L, safetyReportRepository.count())
        assertEquals(0L, userBlockRepository.count())
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(fixture.userId))
    }

    @Test
    fun `second moderation resolution is rejected and only one audit event is created`() {
        stubReadUrls()
        val fixture = createDraftProfile("second-review")
        val photo = savePhoto(fixture.profileId, 1, PhotoModerationStatus.NEEDS_REVIEW)
        val admin = userService.createUser("admin-photo-second-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/admin/profile-photos/${photo.id}/moderation")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content("""{"expectedPhotoVersion":${photo.version},"decision":"APPROVED"}""")
        )
            .andExpect(status().isOk)

        val resolvedVersion = profilePhotoRepository.findById(photo.id).orElseThrow().version
        mockMvc.perform(
            post("/api/admin/profile-photos/${photo.id}/moderation")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content("""{"expectedPhotoVersion":$resolvedVersion,"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_PHOTO_MODERATION_REVIEW_NOT_AVAILABLE")))

        val updated = profilePhotoRepository.findById(photo.id).orElseThrow()
        assertEquals(PhotoModerationStatus.APPROVED, updated.moderationStatus)
        assertEquals(
            1,
            auditEventRepository.findAll().count {
                it.eventType == AuditEventType.PHOTO_MODERATION_UPDATED &&
                    it.aggregateType == AuditAggregateType.PROFILE_PHOTO &&
                    it.aggregateId == photo.id
            }
        )
    }

    @Test
    fun `stale review snapshot is rejected without changing moderation status or auditing`() {
        stubReadUrls()
        val fixture = createDraftProfile("stale-review")
        val photo = savePhoto(fixture.profileId, 1, PhotoModerationStatus.NEEDS_REVIEW)
        val reviewedVersion = photo.version
        val admin = userService.createUser("admin-photo-stale-${UUID.randomUUID()}@example.com")

        val current = profilePhotoRepository.findById(photo.id).orElseThrow()
        current.storageKey = "users/${fixture.userId}/profile-photos/replaced.jpg"
        current.moderationStatus = PhotoModerationStatus.NEEDS_REVIEW
        profilePhotoRepository.saveAndFlush(current)
        entityManager.clear()

        mockMvc.perform(
            post("/api/admin/profile-photos/${photo.id}/moderation")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content("""{"expectedPhotoVersion":$reviewedVersion,"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_PHOTO_MODERATION_REVIEW_NOT_AVAILABLE")))

        val updated = profilePhotoRepository.findById(photo.id).orElseThrow()
        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, updated.moderationStatus)
        assertEquals(
            0,
            auditEventRepository.findAll().count {
                it.eventType == AuditEventType.PHOTO_MODERATION_UPDATED &&
                    it.aggregateType == AuditAggregateType.PROFILE_PHOTO &&
                    it.aggregateId == photo.id
            }
        )
    }

    @Test
    fun `missing expected photo version is rejected as bad request`() {
        val fixture = createDraftProfile("missing-version")
        val photo = savePhoto(fixture.profileId, 1, PhotoModerationStatus.NEEDS_REVIEW)
        val admin = userService.createUser("admin-photo-missing-version-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/admin/profile-photos/${photo.id}/moderation")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isBadRequest)
    }

    private fun createDraftProfile(prefix: String): ProfileFixture {
        val user = userService.createUser("$prefix-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = prefix.split("-")
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } },
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )
        return ProfileFixture(userId = user.id, profileId = profile.id)
    }

    private fun savePhoto(
        profileId: UUID,
        position: Int,
        moderationStatus: PhotoModerationStatus,
        validationStatus: PhotoValidationStatus = PhotoValidationStatus.VALIDATED,
        isPersonPhoto: Boolean = true,
        isFullBody: Boolean = false,
        storageKey: String = "users/$profileId/profile-photos/$position.jpg",
        createdAt: OffsetDateTime = OffsetDateTime.now()
    ): ProfilePhoto =
        profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profileId,
                storageProvider = PhotoStorageProvider.S3,
                storageBucket = "test-bucket",
                storageKey = storageKey,
                position = position,
                isPersonPhoto = isPersonPhoto,
                isFullBody = isFullBody,
                validationStatus = validationStatus,
                moderationStatus = moderationStatus,
                createdAt = createdAt
            )
        )

    private fun stubReadUrls() {
        Mockito.`when`(storageService.getReadUrl(Mockito.anyString(), Mockito.anyString()))
            .thenAnswer { invocation ->
                readUrl(
                    bucket = invocation.getArgument<String>(0),
                    storageKey = invocation.getArgument<String>(1)
                )
            }
    }

    private fun readUrl(
        bucket: String,
        storageKey: String
    ): String =
        "https://media.example.test/$bucket/$storageKey"

    private fun singlePhotoModerationAudit(photoId: UUID) =
        auditEventRepository.findAll().single {
            it.eventType == AuditEventType.PHOTO_MODERATION_UPDATED &&
                it.aggregateType == AuditAggregateType.PROFILE_PHOTO &&
                it.aggregateId == photoId
        }

    private data class ProfileFixture(
        val userId: UUID,
        val profileId: UUID
    )
}
