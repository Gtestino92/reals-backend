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
    fun `home returns profile and queue state`() {
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
            .andExpect(jsonPath("$.queue.inQueue", equalTo(true)))
            .andExpect(jsonPath("$.activeMatches.length()", equalTo(0)))
            .andExpect(jsonPath("$.activeConnections.length()", equalTo(0)))
    }

    @Test
    fun `home returns active first chat discovery data`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profileStatus", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.queue.inQueue", equalTo(false)))
            .andExpect(jsonPath("$.activeMatches.length()", equalTo(1)))
            .andExpect(jsonPath("$.activeMatches[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.activeMatches[0].matchState", equalTo("CHAT_ACTIVE")))
            .andExpect(jsonPath("$.activeMatches[0].firstChat.chatId", equalTo(setup.firstChatId.toString())))
            .andExpect(jsonPath("$.activeMatches[0].firstChat.chatType", equalTo("FIRST_CHAT")))
            .andExpect(jsonPath("$.activeMatches[0].firstChat.chatStatus", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.activeMatches[0].firstChat.expiresAt").exists())
            .andExpect(jsonPath("$.activeMatches[0].firstChat.partner.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.activeMatches[0].firstChat.partner.displayName", equalTo("Match B")))
            .andExpect(jsonPath("$.activeConnections.length()", equalTo(0)))
    }

    @Test
    fun `home keeps visual phase match without active first chat`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeMatches.length()", equalTo(1)))
            .andExpect(jsonPath("$.activeMatches[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.activeMatches[0].matchState", equalTo("VISUAL_PHASE")))
            .andExpect(jsonPath("$.activeMatches[0].firstChat").doesNotExist())
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
            .andExpect(jsonPath("$.activeMatches.length()", equalTo(0)))
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
            .andExpect(jsonPath("$.activeMatches.length()", equalTo(0)))

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeMatches.length()", equalTo(1)))
            .andExpect(jsonPath("$.activeMatches[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.activeMatches[0].matchState", equalTo("VISUAL_PHASE")))
    }

    @Test
    fun `home hides pending scheduling connection until activated`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeMatches.length()", equalTo(0)))
            .andExpect(jsonPath("$.activeConnections.length()", equalTo(0)))
    }

    @Test
    fun `home returns active connection discovery data`() {
        val setup = createAvailableSecondChat()

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profileStatus", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.queue.inQueue", equalTo(false)))
            .andExpect(jsonPath("$.activeMatches.length()", equalTo(0)))
            .andExpect(jsonPath("$.activeConnections.length()", equalTo(1)))
            .andExpect(jsonPath("$.activeConnections[0].connectionId", equalTo(setup.connectionId.toString())))
            .andExpect(jsonPath("$.activeConnections[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.activeConnections[0].connectionState", equalTo("SECOND_CHAT_AVAILABLE")))
            .andExpect(jsonPath("$.activeConnections[0].secondChat.chatType", equalTo("SECOND_CHAT")))
            .andExpect(jsonPath("$.activeConnections[0].secondChat.chatStatus", equalTo("AVAILABLE")))
            .andExpect(jsonPath("$.activeConnections[0].secondChat.expiresAt").exists())
            .andExpect(jsonPath("$.activeConnections[0].secondChat.partner.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.activeConnections[0].secondChat.partner.displayName", equalTo("Match B")))
    }
}
