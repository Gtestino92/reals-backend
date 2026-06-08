package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException
import java.time.LocalDate
import java.util.UUID

class UserFlowGuardrailIntegrationTest : BaseIT() {

    @Test
    fun `profile cannot be activated without required photos`() {
        val user = userService.createUser("draft-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Draft",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.OTHER,
            lookingForGender = LookingForGender.EVERYONE,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
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
    fun `adding a photo to an active profile moves it back to draft`() {
        val userId = createActiveProfile(
            email = "active-add-photo-${UUID.randomUUID()}@example.com",
            displayName = "Active Add Photo",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val profile = profileService.findByUserId(userId)
            ?: error("Profile was not created")

        profileService.addPhoto(
            profileId = profile.id,
            url = "https://example.com/${profile.id}-extra.jpg",
            position = 5,
            isPersonPhoto = false,
            isFullBody = false
        )

        assertEquals(ProfileStatus.DRAFT, profileService.findByIdOrThrow(profile.id).status)
    }

    @Test
    fun `replacing a photo in an active profile moves it back to draft`() {
        val userId = createActiveProfile(
            email = "active-replace-photo-${UUID.randomUUID()}@example.com",
            displayName = "Active Replace Photo",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val profile = profileService.findByUserId(userId)
            ?: error("Profile was not created")

        profileService.replacePhoto(
            profileId = profile.id,
            position = 1,
            url = "https://example.com/${profile.id}-replacement.jpg",
            isPersonPhoto = true,
            isFullBody = true
        )

        assertEquals(ProfileStatus.DRAFT, profileService.findByIdOrThrow(profile.id).status)
    }

    @Test
    fun `deleting a photo from an active profile moves it back to draft`() {
        val userId = createActiveProfile(
            email = "active-delete-photo-${UUID.randomUUID()}@example.com",
            displayName = "Active Delete Photo",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
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
    fun `user cannot delete a photo from another profile`() {
        val ownerUserId = createActiveProfile(
            email = "photo-owner-${UUID.randomUUID()}@example.com",
            displayName = "Photo Owner",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val otherUserId = createActiveProfile(
            email = "photo-other-${UUID.randomUUID()}@example.com",
            displayName = "Photo Other",
            gender = Gender.MALE,
            lookingForGender = LookingForGender.WOMEN
        )
        val ownerProfile = profileService.findByUserId(ownerUserId)
            ?: error("Owner profile was not created")
        val otherProfile = profileService.findByUserId(otherUserId)
            ?: error("Other profile was not created")
        val ownerPhoto = profileService.getPhotos(ownerProfile.id).first()

        val exception = assertThrows<DomainConflictException> {
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
            lookingForGender = LookingForGender.MEN,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
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

        assertThrows<IllegalStateException> {
            chatService.recordChatDecision(
                matchId = setup.matchId,
                userId = setup.userAId,
                decision = ChatContinueDecision.APPROVED
            )
        }
    }

    @Test
    fun `visual approval requires reading partner personal message when present`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Me caiste bien")

        assertThrows<IllegalStateException> {
            visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        }

        assertEquals("Me caiste bien", visualReviewService.getPartnerMessage(setup.matchId, setup.userAId))

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
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
                proposedDateTime = futureHalfHourSlot()
            )
        }
    }

    @Test
    fun `user cannot accept own proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot()
        )

        assertThrows<IllegalStateException> {
            schedulingService.acceptProposal(
                proposalId = proposal.id,
                acceptorUserId = setup.userAId
            )
        }
    }

    @Test
    fun `user can accept partner proposal without submitting own proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot()
        )

        val negotiation = schedulingService.acceptProposal(
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

        assertThrows<IllegalStateException> {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                proposedDateTimes = listOf(
                    slot,
                    slot.plusHours(1),
                    slot.plusHours(2),
                    slot.plusHours(3)
                )
            )
        }
    }
}
