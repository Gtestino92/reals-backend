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
    fun `temporary penalty is effective only before expiresAt`() {
        val user = createActiveProfile(
            email = "temporary-effective-${UUID.randomUUID()}@example.com",
            displayName = "Temporary Effective",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val now = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        val expiresAt = now.plusHours(1)
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = user,
                reason = "Temporary effective violation",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = expiresAt,
                active = true
            )
        )

        val ban = penaltyService.resolveEffectiveBan(userId = user, now = now)

        assertEquals(PenaltyType.TEMPORARY_BAN, ban?.type)
        assertEquals(expiresAt.toInstant(), ban?.expiresAt?.toInstant())
        assertTrue(penaltyService.hasEffectiveBan(userId = user, now = now))
    }

    @Test
    fun `temporary penalty is not effective at exact expiresAt boundary`() {
        val user = createActiveProfile(
            email = "temporary-boundary-${UUID.randomUUID()}@example.com",
            displayName = "Temporary Boundary",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val expiresAt = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = user,
                reason = "Temporary boundary violation",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = expiresAt,
                active = true
            )
        )

        assertNull(penaltyService.resolveEffectiveBan(userId = user, now = expiresAt))
        assertFalse(penaltyService.hasEffectiveBan(userId = user, now = expiresAt))
    }

    @Test
    fun `expired active temporary penalty is not effective before expiration job runs`() {
        val user = createActiveProfile(
            email = "temporary-expired-active-${UUID.randomUUID()}@example.com",
            displayName = "Temporary Expired Active",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val now = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = user,
                reason = "Expired but active temporary violation",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = now.minusSeconds(1),
                active = true
            )
        )

        assertNull(penaltyService.resolveEffectiveBan(userId = user, now = now))
        assertFalse(penaltyService.hasEffectiveBan(userId = user, now = now))
    }

    @Test
    fun `active permanent penalty is effective and inactive permanent penalty is not`() {
        val activeUser = createActiveProfile(
            email = "permanent-active-${UUID.randomUUID()}@example.com",
            displayName = "Permanent Active",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val inactiveUser = createActiveProfile(
            email = "permanent-inactive-${UUID.randomUUID()}@example.com",
            displayName = "Permanent Inactive",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        val now = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = activeUser,
                reason = "Permanent violation",
                type = PenaltyType.PERMANENT_BAN,
                expiresAt = null,
                active = true
            )
        )
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = inactiveUser,
                reason = "Inactive permanent violation",
                type = PenaltyType.PERMANENT_BAN,
                expiresAt = null,
                active = false
            )
        )

        val activeBan = penaltyService.resolveEffectiveBan(userId = activeUser, now = now)

        assertEquals(PenaltyType.PERMANENT_BAN, activeBan?.type)
        assertNull(activeBan?.expiresAt)
        assertNull(penaltyService.resolveEffectiveBan(userId = inactiveUser, now = now))
    }

    @Test
    fun `multiple temporary penalties resolve to latest effective expiresAt`() {
        val user = createActiveProfile(
            email = "temporary-multiple-${UUID.randomUUID()}@example.com",
            displayName = "Temporary Multiple",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val now = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        val monday = now.plusDays(1)
        val wednesday = now.plusDays(3)
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = user,
                reason = "Temporary Monday violation",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = monday,
                active = true
            )
        )
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = user,
                reason = "Temporary Wednesday violation",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = wednesday,
                active = true
            )
        )

        val ban = penaltyService.resolveEffectiveBan(userId = user, now = now)

        assertEquals(PenaltyType.TEMPORARY_BAN, ban?.type)
        assertEquals(wednesday.toInstant(), ban?.expiresAt?.toInstant())
    }

    @Test
    fun `permanent penalty wins over temporary penalties`() {
        val user = createActiveProfile(
            email = "temporary-permanent-${UUID.randomUUID()}@example.com",
            displayName = "Temporary Permanent",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val now = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = user,
                reason = "Temporary violation",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = now.plusDays(3),
                active = true
            )
        )
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = user,
                reason = "Permanent violation",
                type = PenaltyType.PERMANENT_BAN,
                expiresAt = null,
                active = true
            )
        )

        val ban = penaltyService.resolveEffectiveBan(userId = user, now = now)

        assertEquals(PenaltyType.PERMANENT_BAN, ban?.type)
        assertNull(ban?.expiresAt)
    }

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
    fun `temporary penalty from safety report becomes effective account ban`() {
        val setup = createMatchWithFirstChat()
        chatExitService.cancelChatForSafety(
            chatId = setup.firstChatId,
            reporterUserId = setup.userAId,
            details = "Temporary safety report"
        )
        val report = safetyReportRepository.findAll().single()
        val admin = userService.createUser("admin-temporary-${UUID.randomUUID()}@example.com")

        val reviewed = safetyReportService.confirmReportWithPenalty(
            reportId = report.id,
            adminUserId = admin.id,
            penaltyType = PenaltyType.TEMPORARY_BAN,
            durationHours = 72,
            reason = "Temporary safety violation",
            notes = "Confirmed temporary violation"
        )
        val penalty = penaltyRepository.findById(reviewed.penaltyId ?: error("Expected penalty")).orElseThrow()
        val ban = penaltyService.resolveEffectiveBan(userId = setup.userBId, now = penalty.createdAt)

        assertEquals(PenaltyType.TEMPORARY_BAN, penalty.type)
        assertEquals(setup.userBId, penalty.userId)
        assertEquals(report.id, penalty.sourceReportId)
        assertEquals(admin.id, penalty.appliedByUserId)
        assertTrue(penalty.active)
        assertEquals(PenaltyType.TEMPORARY_BAN, ban?.type)
        assertEquals(penalty.expiresAt?.toInstant(), ban?.expiresAt?.toInstant())
    }

    @Test
    fun `effective penalty blocks enqueue and applying penalty removes queued user`() {
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
    fun `basic compatible pair query excludes users with effective penalties`() {
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
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = userA,
                reason = "Penalty left in queue",
                expiresAt = OffsetDateTime.now().plusHours(1)
            )
        )

        val pairs = findBasicCompatiblePairs()

        assertFalse(
            pairs.any {
                (it.userAId == userA && it.userBId == userB) ||
                    (it.userAId == userB && it.userBId == userA)
            }
        )
        assertTrue(matchmakingQueueRepository.existsByUserId(userA))
        assertTrue(matchmakingQueueRepository.existsByUserId(userB))
    }

    @Test
    fun `basic compatible pair query allows expired active temporary penalties`() {
        val userA = createActiveProfile(
            email = "expired-penalty-query-a-${UUID.randomUUID()}@example.com",
            displayName = "Expired Penalty Query A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "expired-penalty-query-b-${UUID.randomUUID()}@example.com",
            displayName = "Expired Penalty Query B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = userA,
                reason = "Expired penalty left in queue",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = OffsetDateTime.now().minusSeconds(1),
                active = true
            )
        )

        val pairs = findBasicCompatiblePairs()

        assertTrue(
            pairs.any {
                (it.userAId == userA && it.userBId == userB) ||
                    (it.userAId == userB && it.userBId == userA)
            }
        )
        assertTrue(matchmakingQueueRepository.existsByUserId(userA))
        assertTrue(matchmakingQueueRepository.existsByUserId(userB))
    }
}
