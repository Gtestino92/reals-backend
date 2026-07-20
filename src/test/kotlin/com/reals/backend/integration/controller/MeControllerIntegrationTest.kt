package com.reals.backend.integration.controller

import com.jayway.jsonpath.JsonPath
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `provision me links legacy user only with verified firebase email`() {
        val existing = userService.createUser("provision-verified-link-${UUID.randomUUID()}@example.com")
        userRepository.flush()
        val firebaseUid = "firebase-${UUID.randomUUID()}"

        mockMvc.perform(
            post("/api/me/provision")
                .with(authenticatedWithFirebase(firebaseUid, existing.email, emailVerified = true))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id", equalTo(existing.id.toString())))

        assertEquals(firebaseUid, userRepository.findById(existing.id).orElseThrow().firebaseUid)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `provision me rejects unverified firebase email legacy link`() {
        val existing = userService.createUser("provision-unverified-link-${UUID.randomUUID()}@example.com")
        userRepository.flush()
        val firebaseUid = "firebase-${UUID.randomUUID()}"

        mockMvc.perform(
            post("/api/me/provision")
                .with(authenticatedWithFirebase(firebaseUid, existing.email, emailVerified = false))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("EMAIL_NOT_VERIFIED")))

        kotlin.test.assertNull(userRepository.findById(existing.id).orElseThrow().firebaseUid)
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
    fun `provision me returns existing backend user from auth context principal`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-context-${UUID.randomUUID()}",
            email = "existing-context-${UUID.randomUUID()}@example.com"
        )

        mockMvc.perform(
            post("/api/me/provision")
                .with(
                    authenticatedWithContext(
                        userId = user.id,
                        firebaseUid = user.firebaseUid,
                        email = user.email,
                        emailVerified = false
                    )
                )
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
    fun `register push token stores token for authenticated user`() {
        val user = userService.createUser("push-token-controller-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            put("/api/me/push-tokens")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "token": "controller-fcm-token",
                      "platform": "ANDROID"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.registered", equalTo(true)))

        val token = pushDeviceTokenRepository.findByToken("controller-fcm-token")
            ?: error("Push token was not stored")
        kotlin.test.assertEquals(user.id, token.userId)
        kotlin.test.assertTrue(token.enabled)
    }

    @Test
    fun `home returns profile and matchmaking state`() {
        val userId = createActiveProfile(
            email = "home-queue-${UUID.randomUUID()}@example.com",
            displayName = "Home Queue",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
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
            .andExpect(jsonPath("$.activeInteractionsSummary.hasPendingSchedulingConnection", equalTo(false)))
            .andExpect(jsonPath("$.activeInteractionsSummary.actionableConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(0)))
    }

    @Test
    fun `full home clears dirty home status without changing version`() {
        val userId = userService.createUser(
            email = "home-clean-dirty-${UUID.randomUUID()}@example.com"
        ).id
        val dirtyStatus = homeStatusService.bump(
            userId = userId,
            reason = "test_dirty_before_full_home"
        )

        kotlin.test.assertEquals(1, dirtyStatus.version)
        kotlin.test.assertTrue(dirtyStatus.dirty)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)

        val cleanStatus = homeStatusService.getOrCreateStatus(userId)
        kotlin.test.assertEquals(dirtyStatus.version, cleanStatus.version)
        kotlin.test.assertFalse(cleanStatus.dirty)
    }

    @Test
    fun `home status endpoint returns dirty false after full home load`() {
        val userId = userService.createUser(
            email = "home-status-clean-after-full-${UUID.randomUUID()}@example.com"
        ).id
        val dirtyStatus = homeStatusService.bump(
            userId = userId,
            reason = "test_status_after_full_home"
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            get("/api/me/home/status")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version", equalTo(dirtyStatus.version.toInt())))
            .andExpect(jsonPath("$.dirty", equalTo(false)))
            .andExpect(jsonPath("$.serverTime").exists())
    }

    @Test
    fun `pending home does not clear dirty home status`() {
        val userId = userService.createUser(
            email = "home-pending-keeps-dirty-${UUID.randomUUID()}@example.com"
        ).id
        val dirtyStatus = homeStatusService.bump(
            userId = userId,
            reason = "test_pending_keeps_dirty"
        )

        mockMvc.perform(
            get("/api/me/home/pending")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version", equalTo(dirtyStatus.version.toInt())))

        val statusAfterPending = homeStatusService.getOrCreateStatus(userId)
        kotlin.test.assertEquals(dirtyStatus.version, statusAfterPending.version)
        kotlin.test.assertTrue(statusAfterPending.dirty)
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
            .andExpect(jsonPath("$.pendingActions[0].visualStartedAt").value(nullValue()))
            .andExpect(jsonPath("$.pendingActions[0].visualExpiresAt").value(nullValue()))
            .andExpect(jsonPath("$.pendingActions[0].partner.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.pendingActions[0].partner.displayName", equalTo("Match B")))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
    }

    @Test
    fun `home does not expose partner first chat decision timing through initial count`() {
        val setup = createMatchWithFirstChat("home-first-chat-privacy")

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions[0].type", equalTo("FIRST_CHAT")))

        chatService.recordChatDecision(
            setup.matchId,
            setup.userAId,
            ChatContinueDecision.APPROVED
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions[0].type", equalTo("FIRST_CHAT")))

        chatService.recordChatDecision(
            setup.matchId,
            setup.userBId,
            ChatContinueDecision.REJECTED
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
    }

    @Test
    fun `home initial count follows visual review action after mutual first chat approval`() {
        val setup = createMatchWithFirstChat("home-first-chat-positive")

        chatService.recordChatDecision(
            setup.matchId,
            setup.userAId,
            ChatContinueDecision.APPROVED
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))

        chatService.recordChatDecision(
            setup.matchId,
            setup.userBId,
            ChatContinueDecision.APPROVED
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions[0].type", equalTo("VISUAL_REVIEW")))
    }

    @Test
    fun `home pending returns lightweight pending data with current version`() {
        val setup = createMatchWithFirstChat()
        val status = homeStatusService.getOrCreateStatus(setup.userAId)

        mockMvc.perform(
            get("/api/me/home/pending")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version", equalTo(status.version.toInt())))
            .andExpect(jsonPath("$.serverTime").exists())
            .andExpect(jsonPath("$.matchmaking").doesNotExist())
            .andExpect(jsonPath("$.activeInteractionsSummary").doesNotExist())
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions[0].type", equalTo("FIRST_CHAT")))
            .andExpect(jsonPath("$.pendingActions[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.pendingActions[0].chatId", equalTo(setup.firstChatId.toString())))
            .andExpect(jsonPath("$.pendingActions[0].visualStartedAt").value(nullValue()))
            .andExpect(jsonPath("$.pendingActions[0].visualExpiresAt").value(nullValue()))
            .andExpect(jsonPath("$.pendingActions[0].partner").doesNotExist())
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(0)))
    }

    @Test
    fun `home returns pending VISUAL_REVIEW action`() {
        val setup = createMatchInVisualPhase()
        val visualReview = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Visual review was not created")
        val expectedVisualStartedAt =
            DateTimeFormatter.ISO_INSTANT.format(visualReview.createdAt.toInstant())
        val expectedVisualExpiresAt =
            DateTimeFormatter.ISO_INSTANT.format(
                (visualReview.expiresAt ?: error("Visual review expiresAt was not set")).toInstant()
            )

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
            .andExpect(jsonPath("$.pendingActions[0].visualStartedAt", equalTo(expectedVisualStartedAt)))
            .andExpect(jsonPath("$.pendingActions[0].visualExpiresAt", equalTo(expectedVisualExpiresAt)))
            .andExpect(jsonPath("$.pendingActions[0].partner.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
    }

    @Test
    fun `home pending returns VISUAL_REVIEW action with authoritative visual timestamps`() {
        val setup = createMatchInVisualPhase()
        val status = homeStatusService.getOrCreateStatus(setup.userAId)
        val visualReview = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Visual review was not created")
        val expectedVisualStartedAt =
            DateTimeFormatter.ISO_INSTANT.format(visualReview.createdAt.toInstant())
        val expectedVisualExpiresAt =
            DateTimeFormatter.ISO_INSTANT.format(
                (visualReview.expiresAt ?: error("Visual review expiresAt was not set")).toInstant()
            )

        mockMvc.perform(
            get("/api/me/home/pending")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version", equalTo(status.version.toInt())))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(1)))
            .andExpect(jsonPath("$.pendingActions[0].type", equalTo("VISUAL_REVIEW")))
            .andExpect(jsonPath("$.pendingActions[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.pendingActions[0].chatId").doesNotExist())
            .andExpect(jsonPath("$.pendingActions[0].visualStartedAt", equalTo(expectedVisualStartedAt)))
            .andExpect(jsonPath("$.pendingActions[0].visualExpiresAt", equalTo(expectedVisualExpiresAt)))
            .andExpect(jsonPath("$.pendingActions[0].partner").doesNotExist())
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(0)))
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
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.hasPendingSchedulingConnection", equalTo(true)))
            .andExpect(jsonPath("$.activeInteractionsSummary.pendingSchedulingConnectionCount").doesNotExist())
            .andExpect(jsonPath("$.activeInteractionsSummary.actionableConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(1)))
            .andExpect(jsonPath("$.passiveNotices[0].type", equalTo("SCHEDULING_PREPARING")))
            .andExpect(jsonPath("$.passiveNotices[0].count").doesNotExist())

        mockMvc.perform(
            get("/api/me/home/pending")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(1)))
            .andExpect(jsonPath("$.passiveNotices[0].type", equalTo("SCHEDULING_PREPARING")))
            .andExpect(jsonPath("$.passiveNotices[0].count").doesNotExist())

        kotlin.test.assertEquals(
            1,
            lockRepository.countByUserIdAndEngagementType(
                setup.userAId,
                EngagementType.CONNECTION
            )
        )
    }

    @Test
    fun `home keeps scheduling pending in matchmaking connection capacity`() {
        val userAId = createActiveProfile(
            email = "home-capacity-a-${UUID.randomUUID()}@example.com",
            displayName = "Home Capacity A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        repeat(2) { index ->
            val userBId = createActiveProfile(
                email = "home-capacity-b-$index-${UUID.randomUUID()}@example.com",
                displayName = "Home Capacity B $index",
                gender = Gender.MALE,
                lookingForGenders = setOf(Gender.FEMALE)
            )
            val match = matchService.createMatch(userAId, userBId)
            chatService.startFirstChat(match.id)
            chatService.recordChatDecision(match.id, userAId, ChatContinueDecision.APPROVED)
            chatService.recordChatDecision(match.id, userBId, ChatContinueDecision.APPROVED)
            visualReviewService.recordDecision(match.id, userAId, VisualDecision.APPROVED)
            visualReviewService.recordDecision(match.id, userBId, VisualDecision.APPROVED)
        }

        kotlin.test.assertEquals(
            2,
            lockRepository.countByUserIdAndEngagementType(
                userAId,
                EngagementType.CONNECTION
            )
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchmaking.canSearch", equalTo(false)))
            .andExpect(
                jsonPath(
                    "$.matchmaking.blockedReason.code",
                    equalTo("ACTIVE_CONNECTION_LIMIT_REACHED")
                )
            )
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.hasPendingSchedulingConnection", equalTo(true)))
            .andExpect(jsonPath("$.activeInteractionsSummary.pendingSchedulingConnectionCount").doesNotExist())
            .andExpect(jsonPath("$.activeInteractionsSummary.actionableConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(1)))
            .andExpect(jsonPath("$.passiveNotices[0].type", equalTo("SCHEDULING_PREPARING")))
            .andExpect(jsonPath("$.passiveNotices[0].count").doesNotExist())

        mockMvc.perform(
            get("/api/me/home/pending")
                .with(authenticatedAs(userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passiveNotices.length()", equalTo(1)))
            .andExpect(jsonPath("$.passiveNotices[0].type", equalTo("SCHEDULING_PREPARING")))
            .andExpect(jsonPath("$.passiveNotices[0].count").doesNotExist())
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
            .andExpect(jsonPath("$.activeInteractionsSummary.hasPendingSchedulingConnection", equalTo(false)))
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
    fun `home returns SECOND_CHAT_SCHEDULED and materialized second chat next steps`() {
        val scheduledSetup = createConnectionInSchedulingPhase()
        val scheduledSlot = futureHalfHourSlot()
        schedulingService.addProposal(scheduledSetup.connectionId, scheduledSetup.userAId, scheduledSlot, 1)
        schedulingService.addProposal(scheduledSetup.connectionId, scheduledSetup.userBId, scheduledSlot, 1)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(scheduledSetup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(1)))
            .andExpect(jsonPath("$.nextSteps[0].type", equalTo("SECOND_CHAT_SCHEDULED")))
            .andExpect(jsonPath("$.nextSteps[0].connectionId", equalTo(scheduledSetup.connectionId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].matchId", equalTo(scheduledSetup.matchId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatId").doesNotExist())
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatType").doesNotExist())
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatStatus").doesNotExist())
            .andExpect(
                jsonPath(
                    "$.nextSteps[0].secondChat.availableAt",
                    equalTo(DateTimeFormatter.ISO_INSTANT.format(scheduledSlot.toInstant()))
                )
            )
            .andExpect(
                jsonPath(
                    "$.nextSteps[0].secondChat.expiresAt",
                    equalTo(DateTimeFormatter.ISO_INSTANT.format(scheduledSlot.plusMinutes(120).toInstant()))
                )
            )
            .andExpect(jsonPath("$.nextSteps[0].secondChat.durationMinutes", equalTo(120)))

        val availableSetup = createActiveSecondChat()
        val activeSecondChat = chatRepository.findByConnectionIdAndChatType(
            availableSetup.connectionId,
            ChatType.SECOND_CHAT
        ) ?: error("Second chat was not created")
        val availableAt = activeSecondChat.availableAt ?: error("Second chat availableAt was not set")

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
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatStatus", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.durationMinutes", equalTo(120)))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.partner.userId", equalTo(availableSetup.userBId.toString())))
            .andExpect { result ->
                val body = result.response.contentAsString
                val actualAvailableAt = OffsetDateTime.parse(
                    JsonPath.read(body, "$.nextSteps[0].secondChat.availableAt")
                )
                val actualExpiresAt = OffsetDateTime.parse(
                    JsonPath.read(body, "$.nextSteps[0].secondChat.expiresAt")
                )
                assertEquals(availableAt.toInstant(), actualAvailableAt.toInstant())
                assertEquals(activeSecondChat.timeoutAt.toInstant(), actualExpiresAt.toInstant())
            }
    }

    @Test
    fun `home returns SECOND_CHAT_READ_ONLY after second chat writable window expires`() {
        val setup = createActiveSecondChat()

        chatRepository.updateTimeoutAt(
            chatId = setup.secondChatId,
            timeoutAt = OffsetDateTime.now().minusSeconds(1)
        )
        chatService.expireSecondChatToReadOnly(setup.secondChatId)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(1)))
            .andExpect(jsonPath("$.nextSteps[0].type", equalTo("SECOND_CHAT_READ_ONLY")))
            .andExpect(jsonPath("$.nextSteps[0].connectionId", equalTo(setup.connectionId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatId", equalTo(setup.secondChatId.toString())))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatType", equalTo("SECOND_CHAT")))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.chatStatus", equalTo("EXPIRED")))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.availableAt").exists())
            .andExpect(jsonPath("$.nextSteps[0].secondChat.expiresAt").exists())
            .andExpect(jsonPath("$.nextSteps[0].secondChat.readOnlyUntil").exists())
            .andExpect(jsonPath("$.nextSteps[0].secondChat.durationMinutes", equalTo(120)))
            .andExpect(jsonPath("$.nextSteps[0].secondChat.partner.userId", equalTo(setup.userBId.toString())))
    }

    @Test
    fun `home excludes expired scheduled second chat without chat`() {
        val setup = createConnectionInSchedulingPhase()
        val scheduledSlot = futureHalfHourSlot()
        schedulingService.addProposal(setup.connectionId, setup.userAId, scheduledSlot, 1)
        schedulingService.addProposal(setup.connectionId, setup.userBId, scheduledSlot, 1)

        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().minusMinutes(121)
        )

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.actionableConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))
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
