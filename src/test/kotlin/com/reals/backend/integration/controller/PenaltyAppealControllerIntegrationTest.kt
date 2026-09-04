package com.reals.backend.integration.controller

import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyAppealDecision
import com.reals.backend.domain.PenaltyAppealStatus
import com.reals.backend.domain.PenaltyType
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.exception.DomainErrorCode
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class PenaltyAppealControllerIntegrationTest : ControllerIT() {

    @Test
    fun `user can view available submit once and view pending appeal`() {
        val userId = createAppealableUser()
        val penalty = penaltyService.createPermanentPenalty(
            userId = userId,
            reason = "Permanent appeal violation"
        )

        mockMvc.perform(
            get("/api/me/ban/appeal")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("AVAILABLE")))
            .andExpect(jsonPath("$.banActive", equalTo(true)))
            .andExpect(jsonPath("$.appealedAt").doesNotExist())
            .andExpect(jsonPath("$.reviewedAt").doesNotExist())
            .andExpect(jsonPath("$.penaltyReason").doesNotExist())
            .andExpect(jsonPath("$.sourceReportId").doesNotExist())
            .andExpect(jsonPath("$.appealReviewNotes").doesNotExist())

        mockMvc.perform(
            post("/api/me/ban/appeal")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"statement":"  Please review this ban.  "}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status", equalTo("PENDING")))
            .andExpect(jsonPath("$.banActive", equalTo(true)))
            .andExpect(jsonPath("$.appealedAt", notNullValue()))

        val afterSubmit = penaltyRepository.findById(penalty.id).orElseThrow()
        assertEquals(PenaltyAppealStatus.PENDING, afterSubmit.appealStatus)
        assertEquals("Please review this ban.", afterSubmit.appealStatement)
        assertTrue(afterSubmit.active)

        mockMvc.perform(
            get("/api/me/ban/appeal")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("PENDING")))
            .andExpect(jsonPath("$.banActive", equalTo(true)))

        mockMvc.perform(
            post("/api/me/ban/appeal")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"statement":"Different statement"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo(DomainErrorCode.PENALTY_APPEAL_ALREADY_SUBMITTED.name)))

        val afterDuplicate = penaltyRepository.findById(penalty.id).orElseThrow()
        assertEquals("Please review this ban.", afterDuplicate.appealStatement)
        assertEquals(1, appealAuditCount(AuditEventType.PENALTY_APPEAL_SUBMITTED, penalty.id))
        val audit = auditEventRepository.findAll().single {
            it.eventType == AuditEventType.PENALTY_APPEAL_SUBMITTED &&
                it.aggregateId == penalty.id
        }
        assertEquals(userId, audit.actorUserId)
        assertEquals(userId, audit.targetUserId)
        assertFalse(audit.metadataJson.orEmpty().contains("Please review this ban."))
    }

    @Test
    fun `user without active permanent ban cannot appeal`() {
        val userId = createAppealableUser("no-permanent")
        penaltyService.createTemporaryPenalty(
            userId = userId,
            reason = "Temporary only",
            duration = Duration.ofHours(1)
        )

        mockMvc.perform(
            get("/api/me/ban/appeal")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo(DomainErrorCode.PENALTY_APPEAL_NOT_AVAILABLE.name)))

        mockMvc.perform(
            post("/api/me/ban/appeal")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"statement":"I want to appeal."}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo(DomainErrorCode.PENALTY_APPEAL_NOT_AVAILABLE.name)))
    }

    @Test
    fun `admin lists pending appeals oldest first and can approve`() {
        val olderUserId = createAppealableUser("older-appeal")
        val newerUserId = createAppealableUser("newer-appeal")
        val adminUserId = userService.createUser("appeal-admin-${UUID.randomUUID()}@example.com").id
        val olderPenalty = pendingPenalty(
            userId = olderUserId,
            reason = "Older internal reason",
            statement = "Older statement",
            appealedAt = OffsetDateTime.parse("2026-09-01T10:00:00Z")
        )
        val newerPenalty = pendingPenalty(
            userId = newerUserId,
            reason = "Newer internal reason",
            statement = "Newer statement",
            appealedAt = OffsetDateTime.parse("2026-09-01T11:00:00Z")
        )

        mockMvc.perform(
            get("/api/admin/penalty-appeals/pending")
                .with(authenticatedAsAdmin(adminUserId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].penaltyId", equalTo(olderPenalty.id.toString())))
            .andExpect(jsonPath("$[0].userId", equalTo(olderUserId.toString())))
            .andExpect(jsonPath("$[0].penaltyReason", equalTo("Older internal reason")))
            .andExpect(jsonPath("$[0].appealStatement", equalTo("Older statement")))
            .andExpect(jsonPath("$[0].sourceReportId").doesNotExist())
            .andExpect(jsonPath("$[1].penaltyId", equalTo(newerPenalty.id.toString())))

        mockMvc.perform(
            post("/api/admin/penalty-appeals/${olderPenalty.id}/decision")
                .with(authenticatedAsAdmin(adminUserId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVE","notes":"  Reviewed and approved.  "}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("APPROVED")))
            .andExpect(jsonPath("$.banActive", equalTo(false)))
            .andExpect(jsonPath("$.reviewedAt", notNullValue()))
            .andExpect(jsonPath("$.appealReviewNotes").doesNotExist())

        val approved = penaltyRepository.findById(olderPenalty.id).orElseThrow()
        assertEquals(PenaltyAppealStatus.APPROVED, approved.appealStatus)
        assertFalse(approved.active)
        assertNotNull(approved.appealReviewedAt)
        assertEquals(adminUserId, approved.appealReviewedByUserId)
        assertEquals("Reviewed and approved.", approved.appealReviewNotes)

        mockMvc.perform(
            get("/api/me/ban/appeal")
                .with(authenticatedAs(olderUserId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("APPROVED")))
            .andExpect(jsonPath("$.banActive", equalTo(false)))

        assertEquals(1, appealAuditCount(AuditEventType.PENALTY_APPEAL_REVIEWED, olderPenalty.id))
        val audit = auditEventRepository.findAll().single {
            it.eventType == AuditEventType.PENALTY_APPEAL_REVIEWED &&
                it.aggregateId == olderPenalty.id
        }
        assertEquals(adminUserId, audit.actorUserId)
        assertEquals(olderUserId, audit.targetUserId)
        assertTrue(audit.metadataJson!!.contains("APPROVE"))
        assertFalse(audit.metadataJson!!.contains("Reviewed and approved."))

        mockMvc.perform(
            post("/api/admin/penalty-appeals/${olderPenalty.id}/decision")
                .with(authenticatedAsAdmin(adminUserId))
                .contentType(jsonContentType)
                .content("""{"decision":"REJECT","notes":"Second decision"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo(DomainErrorCode.PENALTY_APPEAL_NOT_PENDING.name)))

        assertEquals(1, appealAuditCount(AuditEventType.PENALTY_APPEAL_REVIEWED, olderPenalty.id))
    }

    @Test
    fun `admin can reject and banned user can still view rejected result`() {
        val userId = createAppealableUser("reject-appeal")
        val adminUserId = userService.createUser("reject-admin-${UUID.randomUUID()}@example.com").id
        val penalty = pendingPenalty(
            userId = userId,
            reason = "Rejected internal reason",
            statement = "Reject statement"
        )

        mockMvc.perform(
            post("/api/admin/penalty-appeals/${penalty.id}/decision")
                .with(authenticatedAsAdmin(adminUserId))
                .contentType(jsonContentType)
                .content("""{"decision":"REJECT","notes":"Rejection rationale"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("REJECTED")))
            .andExpect(jsonPath("$.banActive", equalTo(true)))

        val rejected = penaltyRepository.findById(penalty.id).orElseThrow()
        assertEquals(PenaltyAppealStatus.REJECTED, rejected.appealStatus)
        assertTrue(rejected.active)

        mockMvc.perform(
            get("/api/me/ban/appeal")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("REJECTED")))
            .andExpect(jsonPath("$.banActive", equalTo(true)))
            .andExpect(jsonPath("$.penaltyReason").doesNotExist())
            .andExpect(jsonPath("$.sourceReportId").doesNotExist())
            .andExpect(jsonPath("$.appealReviewedByUserId").doesNotExist())
            .andExpect(jsonPath("$.appealReviewNotes").doesNotExist())
    }

    @Test
    fun `approval does not reopen contained interactions or reenqueue matchmaking`() {
        val setup = createMatchWithFirstChat("appeal-containment")
        val penalty = penaltyService.createPermanentPenalty(
            userId = setup.userBId,
            reason = "Containment permanent ban"
        )
        penaltyAppealService.submitMyAppeal(
            userId = setup.userBId,
            statement = "Review containment appeal"
        )

        assertFalse(matchmakingQueueRepository.existsByUserId(setup.userBId))
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(setup.firstChatId).orElseThrow().status)

        val adminUserId = userService.createUser("containment-admin-${UUID.randomUUID()}@example.com").id
        mockMvc.perform(
            post("/api/admin/penalty-appeals/${penalty.id}/decision")
                .with(authenticatedAsAdmin(adminUserId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVE","notes":"Approved after review"}""")
        )
            .andExpect(status().isOk)

        assertFalse(matchmakingQueueRepository.existsByUserId(setup.userBId))
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(setup.firstChatId).orElseThrow().status)
    }

    @Test
    fun `approved permanent appeal still leaves independent active temporary ban effective`() {
        val userId = createAppealableUser("temporary-after-approve")
        penaltyService.createTemporaryPenalty(
            userId = userId,
            reason = "Independent temporary",
            duration = Duration.ofHours(3)
        )
        val permanent = penaltyService.createPermanentPenalty(
            userId = userId,
            reason = "Permanent to appeal"
        )
        penaltyAppealService.submitMyAppeal(userId = userId, statement = "Appeal permanent")
        val adminUserId = userService.createUser("temporary-effective-admin-${UUID.randomUUID()}@example.com").id

        penaltyAppealService.decideAppeal(
            penaltyId = permanent.id,
            adminUserId = adminUserId,
            decision = PenaltyAppealDecision.APPROVE,
            notes = "Approve permanent appeal"
        )

        val effectiveBan = penaltyService.resolveEffectiveBan(userId = userId)
        assertEquals(PenaltyType.TEMPORARY_BAN, effectiveBan?.type)
        assertNotNull(effectiveBan?.expiresAt)
    }

    private fun createAppealableUser(prefix: String = "appealable"): UUID =
        createActiveProfile(
            email = "$prefix-${UUID.randomUUID()}@example.com",
            displayName = "Appealable User",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

    private fun pendingPenalty(
        userId: UUID,
        reason: String,
        statement: String,
        appealedAt: OffsetDateTime = OffsetDateTime.parse("2026-09-01T10:00:00Z")
    ): Penalty =
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = userId,
                reason = reason,
                type = PenaltyType.PERMANENT_BAN,
                expiresAt = null,
                active = true,
                appealStatus = PenaltyAppealStatus.PENDING,
                appealStatement = statement,
                appealedAt = appealedAt
            )
        )

    private fun appealAuditCount(
        eventType: AuditEventType,
        penaltyId: UUID
    ): Int =
        auditEventRepository.findAll().count {
            it.eventType == eventType && it.aggregateId == penaltyId
        }
}
