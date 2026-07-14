package com.reals.backend.integration.service

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyType
import com.reals.backend.integration.BaseIT
import com.reals.backend.scheduler.PenaltyExpirationJob
import com.reals.backend.service.exception.DomainConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class SafetyPenaltyIntegrationTest : BaseIT() {

    @Test
    fun `temporary penalty expires but permanent penalty remains active`() {
        val temporaryUser = createActiveProfile(
            email = "temporary-penalty-${UUID.randomUUID()}@example.com",
            displayName = "Temporary Penalty",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val permanentUser = createActiveProfile(
            email = "permanent-penalty-${UUID.randomUUID()}@example.com",
            displayName = "Permanent Penalty",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        val temporaryPenalty = penaltyService.createTemporaryPenalty(
            userId = temporaryUser,
            reason = "Temporary violation",
            duration = Duration.ofHours(1)
        )
        penaltyRepository.updateExpiresAt(
            penaltyId = temporaryPenalty.id,
            expiresAt = OffsetDateTime.now().minusSeconds(1)
        )
        val permanentPenalty = penaltyService.createPermanentPenalty(
            userId = permanentUser,
            reason = "Permanent violation"
        )

        PenaltyExpirationJob(penaltyService).run()

        assertFalse(penaltyRepository.findById(temporaryPenalty.id).orElseThrow().active)
        val reloadedPermanent = penaltyRepository.findById(permanentPenalty.id).orElseThrow()
        assertTrue(reloadedPermanent.active)
        assertEquals(PenaltyType.PERMANENT_BAN, reloadedPermanent.type)
        assertNull(reloadedPermanent.expiresAt)
    }

    @Test
    fun `expired penalty expiration is idempotent`() {
        val user = createActiveProfile(
            email = "idempotent-penalty-${UUID.randomUUID()}@example.com",
            displayName = "Idempotent Penalty",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val penalty = penaltyService.createTemporaryPenalty(
            userId = user,
            reason = "Idempotent temporary violation",
            duration = Duration.ofHours(1)
        )
        penaltyRepository.updateExpiresAt(
            penaltyId = penalty.id,
            expiresAt = OffsetDateTime.now().minusSeconds(1)
        )

        assertTrue(penaltyService.expireOverduePenalty(penalty.id))
        assertFalse(penaltyService.expireOverduePenalty(penalty.id))
        assertFalse(penaltyRepository.findById(penalty.id).orElseThrow().active)
    }

    @Test
    fun `inactive expired penalty is skipped as stale`() {
        val user = createActiveProfile(
            email = "stale-penalty-${UUID.randomUUID()}@example.com",
            displayName = "Stale Penalty",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val penalty = penaltyRepository.saveAndFlush(
            Penalty(
                userId = user,
                reason = "Already inactive temporary violation",
                expiresAt = OffsetDateTime.now().minusSeconds(1),
                active = false
            )
        )

        assertFalse(penaltyService.expireOverduePenalty(penalty.id))
    }

    @Test
    fun `permanent penalty from safety report has no expiration`() {
        val setup = createMatchWithFirstChat()
        chatExitService.cancelChatForSafety(
            chatId = setup.firstChatId,
            reporterUserId = setup.userAId,
            details = "Permanent safety report"
        )
        val report = safetyReportRepository.findAll().single()
        val admin = userService.createUser("admin-permanent-${UUID.randomUUID()}@example.com")

        val reviewed = safetyReportService.confirmReportWithPenalty(
            reportId = report.id,
            adminUserId = admin.id,
            penaltyType = PenaltyType.PERMANENT_BAN,
            durationHours = null,
            reason = "Severe safety violation",
            notes = "Confirmed severe violation"
        )
        val penalty = penaltyRepository.findById(reviewed.penaltyId ?: error("Expected penalty")).orElseThrow()

        assertEquals(PenaltyType.PERMANENT_BAN, penalty.type)
        assertEquals(setup.userBId, penalty.userId)
        assertEquals(report.id, penalty.sourceReportId)
        assertEquals(admin.id, penalty.appliedByUserId)
        assertNull(penalty.expiresAt)
        assertTrue(penalty.active)
    }

    @Test
    fun `active penalty blocks enqueue and applying penalty removes queued user`() {
        val user = createActiveProfile(
            email = "queue-removed-by-penalty-${UUID.randomUUID()}@example.com",
            displayName = "Queue Removed By Penalty",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        enqueueForMatchmaking(user)
        assertTrue(matchmakingQueueRepository.existsByUserId(user))

        penaltyService.createTemporaryPenalty(
            userId = user,
            reason = "Queue removal penalty",
            duration = Duration.ofHours(2)
        )

        assertFalse(matchmakingQueueRepository.existsByUserId(user))
        assertThrows<DomainConflictException> {
            enqueueForMatchmaking(user)
        }
    }

    @Test
    fun `basic compatible pair query excludes users with active penalties`() {
        val userA = createActiveProfile(
            email = "penalty-query-a-${UUID.randomUUID()}@example.com",
            displayName = "Penalty Query A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "penalty-query-b-${UUID.randomUUID()}@example.com",
            displayName = "Penalty Query B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)
        penaltyRepository.save(
            Penalty(
                userId = userA,
                reason = "Penalty left in queue",
                expiresAt = OffsetDateTime.now().plusHours(1)
            )
        )

        val pairs = findBasicCompatiblePairs()

        assertTrue(pairs.isEmpty())
        assertTrue(matchmakingQueueRepository.existsByUserId(userA))
        assertTrue(matchmakingQueueRepository.existsByUserId(userB))
    }
}
