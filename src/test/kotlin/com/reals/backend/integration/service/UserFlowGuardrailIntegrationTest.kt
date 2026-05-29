package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
            bio = null
        )

        assertThrows<IllegalStateException> {
            profileService.activateProfile(profile.id)
        }

        assertEquals(ProfileStatus.DRAFT, profileService.findByIdOrThrow(profile.id).status)
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
            bio = null
        )

        assertThrows<IllegalStateException> {
            matchmakingService.enqueue(user.id)
        }
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

        assertThrows<IllegalStateException> {
            chatService.sendMessage(setup.firstChatId, stranger.id, "No pertenezco a este match")
        }
    }

    @Test
    fun `non participant cannot add scheduling proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val stranger = userService.createUser("proposal-stranger-${UUID.randomUUID()}@example.com")

        assertThrows<IllegalStateException> {
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
