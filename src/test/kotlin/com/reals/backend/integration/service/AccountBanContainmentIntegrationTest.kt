package com.reals.backend.integration.service

import com.reals.backend.controller.dto.CreateAdminSafetyReportRequest
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.PenaltyType
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.MeHomeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import java.util.UUID

@TestPropertySource(
    properties = [
        "user-reliability.enabled=true"
    ]
)
class AccountBanContainmentIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var meHomeService: MeHomeService

    @Test
    fun `temporary ban during first chat contains engagement without reliability events`() {
        val setup = createMatchWithFirstChat("ban-first-chat")
        val admin = userService.createUser("ban-first-chat-admin-${UUID.randomUUID()}@example.com")
        enqueueForMatchmaking(setup.userBId)
        val counterpartHomeVersion = homeStatusService.getOrCreateStatus(setup.userAId).version
        val reliabilityEventsBefore = reliabilityEventCountFor(setup.userAId, setup.userBId)

        val report = safetyReportService.createAdminReport(
            adminUserId = admin.id,
            request = CreateAdminSafetyReportRequest(
                reportedUserId = setup.userBId,
                reporterUserId = setup.userAId,
                contextType = SafetyReportContextType.CHAT,
                chatId = setup.firstChatId,
                reason = SafetyReportReason.HARASSMENT,
                details = "Admin-observed unsafe chat behavior"
            )
        )

        assertEquals(SafetyReportStatus.PENDING, report.status)
        assertNull(report.penaltyId)
        assertNull(penaltyService.resolveEffectiveBan(setup.userBId))
        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(setup.firstChatId).orElseThrow().status)

        val reviewed = safetyReportService.confirmReportWithPenalty(
            reportId = report.id,
            adminUserId = admin.id,
            penaltyType = PenaltyType.TEMPORARY_BAN,
            durationHours = 24,
            reason = "Confirmed harassment",
            notes = "Confirmed by moderator"
        )
        val penalty = penaltyRepository.findById(reviewed.penaltyId ?: error("Expected penalty")).orElseThrow()
        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()

        assertEquals(PenaltyType.TEMPORARY_BAN, penalty.type)
        assertEquals(setup.userBId, penalty.userId)
        assertEquals(PenaltyType.TEMPORARY_BAN, penaltyService.resolveEffectiveBan(setup.userBId)?.type)
        assertFalse(matchmakingQueueRepository.existsByUserId(setup.userBId))
        assertEquals(ChatStatus.CANCELLED, chat.status)
        assertEquals(ChatEndReason.USER_BANNED, chat.endedReason)
        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertTrue(homeStatusRepository.findById(setup.userAId).orElseThrow().version > counterpartHomeVersion)
        assertEquals(reliabilityEventsBefore, reliabilityEventCountFor(setup.userAId, setup.userBId))
    }

    @Test
    fun `permanent ban during visual phase closes match without behavioral reliability`() {
        val setup = createMatchInVisualPhase()
        val admin = userService.createUser("ban-visual-admin-${UUID.randomUUID()}@example.com")
        val reliabilityEventsBefore = reliabilityEventCountFor(setup.userAId, setup.userBId)
        val report = createAdminVisualReport(admin.id, setup.userAId, setup.userBId, setup.matchId)

        val reviewed = safetyReportService.confirmReportWithPenalty(
            reportId = report.id,
            adminUserId = admin.id,
            penaltyType = PenaltyType.PERMANENT_BAN,
            durationHours = null,
            reason = "Confirmed severe visual-phase violation",
            notes = "Confirmed by moderator"
        )

        assertEquals(SafetyReportStatus.CONFIRMED, reviewed.status)
        assertEquals(PenaltyType.PERMANENT_BAN, penaltyService.resolveEffectiveBan(setup.userBId)?.type)
        assertEquals(MatchState.VISUAL_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertEquals(0, meHomeService.getHome(setup.userAId).activeInteractionsSummary.activeInitialCount)
        assertEquals(reliabilityEventsBefore, reliabilityEventCountFor(setup.userAId, setup.userBId))
    }

    @Test
    fun `ban during scheduling fails negotiation and closes connection`() {
        val setup = createConnectionInSchedulingPhase()
        val admin = userService.createUser("ban-scheduling-admin-${UUID.randomUUID()}@example.com")
        val reliabilityEventsBefore = reliabilityEventCountFor(setup.userAId, setup.userBId)
        val report = createAdminUserReport(admin.id, setup.userBId)

        safetyReportService.confirmReportWithPenalty(
            reportId = report.id,
            adminUserId = admin.id,
            penaltyType = PenaltyType.TEMPORARY_BAN,
            durationHours = 12,
            reason = "Confirmed scheduling-phase violation",
            notes = "Confirmed by moderator"
        )

        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(NegotiationStatus.FAILED, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
        assertEquals(0, meHomeService.getHome(setup.userAId).activeInteractionsSummary.activeConnectionCount)
        assertEquals(reliabilityEventsBefore, reliabilityEventCountFor(setup.userAId, setup.userBId))
    }

    @Test
    fun `ban during second chat cancels chat closes connection and does not create behavior events`() {
        val setup = createActiveSecondChat()
        val admin = userService.createUser("ban-second-chat-admin-${UUID.randomUUID()}@example.com")
        val reliabilityEventsBefore = reliabilityEventCountFor(setup.userAId, setup.userBId)
        val report = createAdminUserReport(admin.id, setup.userBId)

        safetyReportService.confirmReportWithPenalty(
            reportId = report.id,
            adminUserId = admin.id,
            penaltyType = PenaltyType.PERMANENT_BAN,
            durationHours = null,
            reason = "Confirmed second-chat violation",
            notes = "Confirmed by moderator"
        )
        val secondChat = chatRepository.findById(setup.secondChatId).orElseThrow()

        assertEquals(ChatStatus.CANCELLED, secondChat.status)
        assertEquals(ChatEndReason.USER_BANNED, secondChat.endedReason)
        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
        assertEquals(0, meHomeService.getHome(setup.userAId).activeInteractionsSummary.activeConnectionCount)
        assertEquals(reliabilityEventsBefore, reliabilityEventCountFor(setup.userAId, setup.userBId))
    }

    @Test
    fun `dismissed admin report does not contain reported user`() {
        val setup = createConnectionInSchedulingPhase()
        val admin = userService.createUser("ban-dismiss-admin-${UUID.randomUUID()}@example.com")
        val report = createAdminUserReport(admin.id, setup.userBId)

        safetyReportService.dismissReport(
            reportId = report.id,
            adminUserId = admin.id,
            notes = "Dismissed by moderator"
        )

        assertNull(penaltyService.resolveEffectiveBan(setup.userBId))
        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(NegotiationStatus.PENDING, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    fun `temporary ban expiry does not reopen contained engagement and later ban is idempotent`() {
        val setup = createMatchWithFirstChat("ban-expiry")
        val admin = userService.createUser("ban-expiry-admin-${UUID.randomUUID()}@example.com")
        val report = createAdminUserReport(admin.id, setup.userBId)

        val reviewed = safetyReportService.confirmReportWithPenalty(
            reportId = report.id,
            adminUserId = admin.id,
            penaltyType = PenaltyType.TEMPORARY_BAN,
            durationHours = 1,
            reason = "Confirmed temporary violation",
            notes = "Confirmed by moderator"
        )
        val penalty = penaltyRepository.findById(reviewed.penaltyId ?: error("Expected penalty")).orElseThrow()
        val expiredAt = requireNotNull(penalty.expiresAt)

        assertNull(penaltyService.resolveEffectiveBan(setup.userBId, now = expiredAt))
        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(setup.firstChatId).orElseThrow().status)
        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)

        val secondReport = createAdminUserReport(admin.id, setup.userBId)
        safetyReportService.confirmReportWithPenalty(
            reportId = secondReport.id,
            adminUserId = admin.id,
            penaltyType = PenaltyType.PERMANENT_BAN,
            durationHours = null,
            reason = "Confirmed repeated violation",
            notes = "Confirmed by moderator"
        )

        assertEquals(PenaltyType.PERMANENT_BAN, penaltyService.resolveEffectiveBan(setup.userBId)?.type)
        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(setup.firstChatId).orElseThrow().status)
        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
    }

    private fun createAdminUserReport(
        adminUserId: UUID,
        reportedUserId: UUID
    ) =
        safetyReportService.createAdminReport(
            adminUserId = adminUserId,
            request = CreateAdminSafetyReportRequest(
                reportedUserId = reportedUserId,
                contextType = SafetyReportContextType.USER,
                reason = SafetyReportReason.OTHER,
                details = "Admin-created moderation report"
            )
        )

    private fun createAdminVisualReport(
        adminUserId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
        matchId: UUID
    ) =
        safetyReportService.createAdminReport(
            adminUserId = adminUserId,
            request = CreateAdminSafetyReportRequest(
                reportedUserId = reportedUserId,
                reporterUserId = reporterUserId,
                contextType = SafetyReportContextType.VISUAL_PROFILE,
                matchId = matchId,
                reason = SafetyReportReason.OTHER,
                details = "Admin-created visual moderation report"
            )
        )

    private fun reliabilityEventCountFor(vararg userIds: UUID): Int {
        val userIdSet = userIds.toSet()
        return userReliabilityEventRepository.findAll().count { it.userId in userIdSet }
    }
}
