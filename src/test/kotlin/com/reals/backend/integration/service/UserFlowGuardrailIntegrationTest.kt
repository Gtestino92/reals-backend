package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.StoredObject
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

class UserFlowGuardrailIntegrationTest : BaseIT() {

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @Test
    fun `profile cannot be activated without required photos`() {
        val user = userService.createUser("draft-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Draft",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.OTHER,
            lookingForGenders = Gender.entries.toSet(),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            bio = null,
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        val exception = assertThrows<DomainConflictException> {
            profileService.activateProfile(profile.id)
        }

        assertEquals(DomainErrorCode.PROFILE_PHOTOS_REQUIRED, exception.code)
        assertEquals(ProfileStatus.DRAFT, profileService.findByIdOrThrow(profile.id).status)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `adding a photo to an active profile moves it back to draft`() {
        val userId = createActiveProfile(
            email = "active-add-photo-${UUID.randomUUID()}@example.com",
            displayName = "Active Add Photo",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val profile = profileService.findByUserId(userId)
            ?: error("Profile was not created")

        stubStorageUpload(
            StoredObject(
                bucket = "test-bucket",
                key = "users/$userId/profile-photos/extra.jpg",
                contentType = MediaType.IMAGE_JPEG_VALUE,
                sizeBytes = jpegBytes().size.toLong()
            )
        )

        profileService.uploadPhoto(
            profileId = profile.id,
            position = 5,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = jpegBytes()
        )

        assertEquals(ProfileStatus.DRAFT, profileService.findByIdOrThrow(profile.id).status)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `replacing a photo in an active profile moves it back to draft`() {
        val userId = createActiveProfile(
            email = "active-replace-photo-${UUID.randomUUID()}@example.com",
            displayName = "Active Replace Photo",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val profile = profileService.findByUserId(userId)
            ?: error("Profile was not created")
        val photo = profileService.getPhotos(profile.id).first()

        stubStorageUpload(
            StoredObject(
                bucket = "test-bucket",
                key = "users/$userId/profile-photos/replacement.jpg",
                contentType = MediaType.IMAGE_JPEG_VALUE,
                sizeBytes = jpegBytes().size.toLong()
            )
        )

        profileService.replacePhoto(
            profileId = profile.id,
            photoId = photo.id,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = jpegBytes()
        )

        assertEquals(ProfileStatus.DRAFT, profileService.findByIdOrThrow(profile.id).status)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `deleting a photo from an active profile moves it back to draft`() {
        val userId = createActiveProfile(
            email = "active-delete-photo-${UUID.randomUUID()}@example.com",
            displayName = "Active Delete Photo",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val profile = profileService.findByUserId(userId)
            ?: error("Profile was not created")

        val photo = profileService.getPhotos(profile.id)[0]
        profileService.deletePhoto(
            profileId = profile.id,
            photoId = photo.id
        )

        assertEquals(ProfileStatus.DRAFT, profileService.findByIdOrThrow(profile.id).status)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `user cannot delete a photo from another profile`() {
        val ownerUserId = createActiveProfile(
            email = "photo-owner-${UUID.randomUUID()}@example.com",
            displayName = "Photo Owner",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val otherUserId = createActiveProfile(
            email = "photo-other-${UUID.randomUUID()}@example.com",
            displayName = "Photo Other",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        val ownerProfile = profileService.findByUserId(ownerUserId)
            ?: error("Owner profile was not created")
        val otherProfile = profileService.findByUserId(otherUserId)
            ?: error("Other profile was not created")
        val ownerPhoto = profileService.getPhotos(ownerProfile.id).first()

        val exception = assertThrows<DomainNotFoundException> {
            profileService.deletePhoto(
                profileId = otherProfile.id,
                photoId = ownerPhoto.id
            )
        }

        assertEquals(DomainErrorCode.PROFILE_PHOTO_NOT_FOUND, exception.code)
        assertTrue(profileService.getPhotos(ownerProfile.id).any { it.id == ownerPhoto.id })
    }

    @Test
    fun `draft profile cannot enter matchmaking`() {
        val user = userService.createUser("queue-draft-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Draft Queue",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            bio = null,
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        val exception = assertThrows<DomainConflictException> {
            enqueueForMatchmaking(user.id)
        }

        assertEquals(DomainErrorCode.PROFILE_NOT_ACTIVE, exception.code)
    }

    @Test
    fun `chat decision cannot be submitted twice by the same user`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(
            matchId = setup.matchId,
            userId = setup.userAId,
            decision = ChatContinueDecision.APPROVED
        )

        val exception = assertThrows<DomainConflictException> {
            chatService.recordChatDecision(
                matchId = setup.matchId,
                userId = setup.userAId,
                decision = ChatContinueDecision.APPROVED
            )
        }
        assertEquals(DomainErrorCode.CHAT_DECISION_ALREADY_SUBMITTED, exception.code)
    }

    @Test
    fun `chat decision cannot be submitted after first chat is no longer actionable`() {
        val setup = createMatchWithFirstChat()
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        val exception = assertThrows<DomainConflictException> {
            chatService.recordChatDecision(
                matchId = setup.matchId,
                userId = setup.userAId,
                decision = ChatContinueDecision.APPROVED
            )
        }
        assertEquals(DomainErrorCode.CHAT_DECISION_NOT_AVAILABLE, exception.code)
    }

    @Test
    fun `chat decision cannot be submitted while mutual cancellation is pending`() {
        val setup = createMatchWithFirstChat()
        chatExitService.requestMutualCancellation(
            chatId = setup.firstChatId,
            requesterUserId = setup.userAId
        )

        val exception = assertThrows<DomainConflictException> {
            chatService.recordChatDecision(
                matchId = setup.matchId,
                userId = setup.userBId,
                decision = ChatContinueDecision.APPROVED
            )
        }
        assertEquals(DomainErrorCode.CHAT_MUTUAL_CANCELLATION_PENDING, exception.code)
    }

    @Test
    fun `visual decision does not require reading partner personal message when present`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Me caiste bien")

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)

        assertEquals("Me caiste bien", visualReviewService.getPartnerMessage(setup.matchId, setup.userAId))
    }

    @Test
    fun `non participant cannot send a chat message`() {
        val setup = createMatchWithFirstChat()
        val stranger = userService.createUser("stranger-${UUID.randomUUID()}@example.com")

        assertThrows<AccessDeniedException> {
            chatService.sendMessage(setup.firstChatId, stranger.id, "No pertenezco a este match")
        }
    }

    @Test
    fun `non participant cannot add scheduling proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val stranger = userService.createUser("proposal-stranger-${UUID.randomUUID()}@example.com")

        assertThrows<AccessDeniedException> {
            schedulingService.addProposal(
                connectionId = setup.connectionId,
                userId = stranger.id,
                proposedDateTime = futureHalfHourSlot(),
                expectedRoundNumber = 1
            )
        }
    }

    @Test
    fun `user cannot accept own proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot(),
            expectedRoundNumber = 1
        )

        val exception = assertThrows<DomainConflictException> {
            schedulingService.acceptProposal(
                connectionId = setup.connectionId,
                proposalId = proposal.id,
                acceptorUserId = setup.userAId
            )
        }
        assertEquals(DomainErrorCode.SCHEDULING_CANNOT_ACCEPT_OWN_PROPOSAL, exception.code)
    }

    @Test
    fun `user can accept partner proposal without submitting own proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot(),
            expectedRoundNumber = 1
        )

        val negotiation = schedulingService.acceptProposal(
            connectionId = setup.connectionId,
            proposalId = proposal.id,
            acceptorUserId = setup.userBId
        )

        assertEquals(NegotiationStatus.CONFIRMED, negotiation.status)
        assertEquals(proposal.proposedDateTime.toInstant(), negotiation.confirmedDateTime?.toInstant())
    }

    @Test
    fun `proposal list cannot exceed configured maximum`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        val exception = assertThrows<DomainBadRequestException> {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(
                    slot,
                    slot.plusHours(1),
                    slot.plusHours(2),
                    slot.plusHours(3)
                )
            )
        }
        assertEquals(DomainErrorCode.SCHEDULING_INVALID_PROPOSALS, exception.code)
    }

    private fun stubStorageUpload(storedObject: StoredObject) {
        Mockito.`when`(
            storageService.profilePhotoBucket()
        ).thenReturn(storedObject.bucket)

        Mockito.`when`(
            storageService.profilePhotoObjectKey(
                anyUuid(),
                anyUuid(),
                eqString(MediaType.IMAGE_JPEG_VALUE)
            )
        ).thenReturn(storedObject.key)

        Mockito.`when`(
            storageService.uploadProfilePhoto(
                anyUuid(),
                anyUuid(),
                eqString(MediaType.IMAGE_JPEG_VALUE),
                anyByteArray()
            )
        ).thenReturn(storedObject)

        Mockito.`when`(storageService.getReadUrl(storedObject.bucket, storedObject.key))
            .thenReturn("http://localhost:9000/test-bucket/${storedObject.key}")
    }

    private fun jpegBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }

    private fun anyUuid(): UUID {
        any(UUID::class.java)
        return UUID.randomUUID()
    }

    private fun anyByteArray(): ByteArray {
        any(ByteArray::class.java)
        return byteArrayOf()
    }

    private fun eqString(value: String): String {
        eq(value)
        return value
    }
}
