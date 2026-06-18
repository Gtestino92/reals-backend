package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.UUID

class MeControllerIntegrationTest : ControllerIT() {

    @Test
    fun `provision me creates backend user from firebase principal`() {
        val firebaseUid = "firebase-${UUID.randomUUID()}"
        val email = "firebase-${UUID.randomUUID()}@example.com"

        mockMvc.perform(
            post("/api/me/provision")
                .with(authenticatedWithFirebase(firebaseUid, email))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email", equalTo(email)))

        assertNotNull(userService.findByFirebaseUid(firebaseUid))
    }

    @Test
    fun `provision me returns existing backend user`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-${UUID.randomUUID()}",
            email = "existing-${UUID.randomUUID()}@example.com"
        )

        mockMvc.perform(
            post("/api/me/provision")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.email", equalTo(user.email)))
    }

    @Test
    fun `unprovisioned firebase principal cannot access user endpoints`() {
        mockMvc.perform(
            get("/api/me")
                .with(
                    authenticatedWithFirebase(
                        firebaseUid = "firebase-${UUID.randomUUID()}",
                        email = "unprovisioned-${UUID.randomUUID()}@example.com"
                    )
                )
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code", equalTo("ACCESS_DENIED")))
    }

    @Test
    fun `delete me soft deletes authenticated user`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-${UUID.randomUUID()}",
            email = "delete-me-${UUID.randomUUID()}@example.com"
        )

        mockMvc.perform(
            delete("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)

        val deletedUser = userService.findByIdOrThrow(user.id)
        kotlin.test.assertEquals(com.reals.backend.domain.UserStatus.DELETED, deletedUser.status)
        kotlin.test.assertNotNull(deletedUser.deletedAt)
        kotlin.test.assertNotNull(deletedUser.deletionFinalizesAt)
        kotlin.test.assertEquals(user.email, deletedUser.email)
    }

    @Test
    fun `delete me returns conflict when account is already deleted`() {
        val user = userService.createUser("already-deleted-${UUID.randomUUID()}@example.com")
        userService.deleteUser(user.id)

        mockMvc.perform(
            delete("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("DOMAIN_CONFLICT")))
    }

    @Test
    fun `reactivate me restores deleted authenticated user`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-${UUID.randomUUID()}",
            email = "reactivate-me-${UUID.randomUUID()}@example.com"
        )
        userService.deleteUser(user.id)

        mockMvc.perform(
            post("/api/me/reactivation")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.email", equalTo(user.email)))
            .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.deletedAt").doesNotExist())
            .andExpect(jsonPath("$.deletionFinalizesAt").doesNotExist())
    }

    @Test
    fun `get me returns pending deletion account state`() {
        val user = userService.createUser("deleted-me-${UUID.randomUUID()}@example.com")
        userService.deleteUser(user.id)

        mockMvc.perform(
            get("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.email", equalTo(user.email)))
            .andExpect(jsonPath("$.status", equalTo("DELETED")))
            .andExpect(jsonPath("$.deletedAt", notNullValue()))
            .andExpect(jsonPath("$.deletionFinalizesAt", notNullValue()))
    }

    @Test
    fun `home returns profile and matchmaking state`() {
        val userId = createActiveProfile(
            email = "home-queue-${UUID.randomUUID()}@example.com",
            displayName = "Home Queue",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        enqueueForMatchmaking(userId)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profileStatus", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.matchmaking.inQueue", equalTo(true)))
            .andExpect(jsonPath("$.matchmaking.canSearch", equalTo(false)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.pendingSchedulingConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.actionableConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(0)))
    }

    @Test
    fun `home returns pending FIRST_CHAT action`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profileStatus", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.matchmaking.inQueue", equalTo(false)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(1)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions[0].type", equalTo("FIRST_CHAT")))
            .andExpect(jsonPath("$.pendingActions[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.pendingActions[0].chatId", equalTo(setup.firstChatId.toString())))
            .andExpect(jsonPath("$.pendingActions[0].partner.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.pendingActions[0].partner.displayName", equalTo("Match B")))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
    }

    @Test
    fun `home returns pending VISUAL_REVIEW action`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(1)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions[0].type", equalTo("VISUAL_REVIEW")))
            .andExpect(jsonPath("$.pendingActions[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.pendingActions[0].chatId").doesNotExist())
            .andExpect(jsonPath("$.pendingActions[0].partner.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
    }

    @Test
    fun `home hides expired visual phase match`() {
        val setup = createMatchInVisualPhase()
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = OffsetDateTime.now().minusMinutes(1)
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
    }

    @Test
    fun `home hides visual review after current user decides but keeps it for partner`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(
            matchId = setup.matchId,
            userId = setup.userAId,
            decision = VisualDecision.REJECTED
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(1)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions[0].type", equalTo("VISUAL_REVIEW")))
            .andExpect(jsonPath("$.pendingActions[0].matchId", equalTo(setup.matchId.toString())))
    }

    @Test
    fun `home returns SCHEDULING_PREPARING passive notice for scheduling pending`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(1)))
            .andExpect(jsonPath("$.activeInteractionsSummary.pendingSchedulingConnectionCount", equalTo(1)))
            .andExpect(jsonPath("$.activeInteractionsSummary.actionableConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(1)))
            .andExpect(jsonPath("$.passiveNotices[0].type", equalTo("SCHEDULING_PREPARING")))
            .andExpect(jsonPath("$.passiveNotices[0].count", equalTo(1)))
    }

    @Test
    fun `home returns SCHEDULING next step only in scheduling phase`() {
        val setup = createConnectionInSchedulingPhase()

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(1)))
            .andExpect(jsonPath("$.activeInteractionsSummary.pendingSchedulingConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.actionableConnectionCount", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(1)))
            .andExpect(jsonPath("$.nextSteps[0].type", equalTo("SCHEDULING")))
            .andExpect(jsonPath("$.nextSteps[0].connectionId", equalTo(setup.connectionId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].partner.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].secondChat").doesNotExist())
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(0)))
    }

    @Test
    fun `home returns SECOND_CHAT_SCHEDULED and SECOND_CHAT_AVAILABLE next steps`() {
        val scheduledSetup = createConnectionInSchedulingPhase()
        val scheduledSlot = futureHalfHourSlot()
        schedulingService.addProposal(scheduledSetup.connectionId, scheduledSetup.userAId, scheduledSlot)
        schedulingService.addProposal(scheduledSetup.connectionId, scheduledSetup.userBId, scheduledSlot)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(scheduledSetup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(1)))
            .andExpect(jsonPath("$.nextSteps[0].type", equalTo("SECOND_CHAT_SCHEDULED")))
            .andExpect(jsonPath("$.nextSteps[0].connectionId", equalTo(scheduledSetup.connectionId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].matchId", equalTo(scheduledSetup.matchId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].secondChat").doesNotExist())

        val availableSetup = createAvailableSecondChat()

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(availableSetup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(1)))
            .andExpect(jsonPath("$.nextSteps[0].type", equalTo("SECOND_CHAT_AVAILABLE")))
            .andExpect(jsonPath("$.nextSteps[0].connectionId", equalTo(availableSetup.connectionId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].matchId", equalTo(availableSetup.matchId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatId").exists())
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatType", equalTo("SECOND_CHAT")))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatStatus", equalTo("AVAILABLE")))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.partner.userId", equalTo(availableSetup.userBId.toString())))
    }

    @Test
    fun `home excludes closed matches and connections`() {
        val matchSetup = createMatchWithFirstChat()
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = matchSetup.firstChatId,
                requesterUserId = matchSetup.userAId
            )
        chatExitService.acceptMutualCancellation(
            chatId = matchSetup.firstChatId,
            requestId = exitRequest.id,
            responderUserId = matchSetup.userBId
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(matchSetup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))

        val connectionSetup = createActiveSecondChat()
        val secondChatExitRequest =
            chatExitService.requestMutualCancellation(
                chatId = connectionSetup.secondChatId,
                requesterUserId = connectionSetup.userAId
            )
        chatExitService.acceptMutualCancellation(
            chatId = connectionSetup.secondChatId,
            requestId = secondChatExitRequest.id,
            responderUserId = connectionSetup.userBId
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(connectionSetup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
    }
}
