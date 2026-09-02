package com.reals.backend.integration.service

import com.reals.backend.controller.dto.CreateAdminSafetyReportRequest
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyType
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.MeHomeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.test.context.TestPropertySource
import java.time.Duration
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

    @Value("\${account.ban.temporary-resume-margin-minutes:30}")
    private var temporaryResumeMarginMinutes: Long = 0

    @Value("\${chat.second-chat.entry-window-minutes:20}")
    private var secondChatEntryWindowMinutes: Long = 0

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
    fun `permanent ban during scheduling fails negotiation and closes connection`() {
        val setup = createConnectionInSchedulingPhase()
        val admin = userService.createUser("ban-scheduling-admin-${UUID.randomUUID()}@example.com")
        val reliabilityEventsBefore = reliabilityEventCountFor(setup.userAId, setup.userBId)
        val report = createAdminUserReport(admin.id, setup.userBId)

        safetyReportService.confirmReportWithPenalty(
            reportId = report.id,
            adminUserId = admin.id,
            penaltyType = PenaltyType.PERMANENT_BAN,
            durationHours = null,
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
    fun `permanent ban during first chat full contains engagement and removes queue entry`() {
        val setup = createMatchWithFirstChat("permanent-ban-first-chat")
        val now = fixedDecisionTime()
        enqueueForMatchmaking(setup.userBId)
        val reliabilityEventsBefore = reliabilityEventCountFor(setup.userAId, setup.userBId)

        penaltyService.createPermanentPenalty(
            userId = setup.userBId,
            reason = "Confirmed permanent violation",
            now = now
        )
        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()

        assertFalse(matchmakingQueueRepository.existsByUserId(setup.userBId))
        assertEquals(ChatStatus.CANCELLED, chat.status)
        assertEquals(ChatEndReason.USER_BANNED, chat.endedReason)
        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertEquals(reliabilityEventsBefore, reliabilityEventCountFor(setup.userAId, setup.userBId))
    }

    @Test
    fun `temporary ban preserves pending visual decision with more than resume margin`() {
        val setup = createMatchInVisualPhase()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin().plusSeconds(1)
        )

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.MATCH))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.MATCH))
    }

    @Test
    fun `temporary ban preserves pending visual decision at exact resume margin`() {
        val setup = createMatchInVisualPhase()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin()
        )

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.MATCH))
    }

    @Test
    fun `temporary ban contains pending visual decision with less than resume margin`() {
        val setup = createMatchInVisualPhase()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        val reliabilityEventsBefore = reliabilityEventCountFor(setup.userAId, setup.userBId)
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin().minusSeconds(1)
        )

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(MatchState.VISUAL_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertEquals(reliabilityEventsBefore, reliabilityEventCountFor(setup.userAId, setup.userBId))
    }

    @Test
    fun `temporary ban preserves visual phase when banned participant already decided`() {
        val setup = createMatchInVisualPhase()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = banExpiresAt.plusMinutes(5)
        )

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.MATCH))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.MATCH))
    }

    @Test
    fun `temporary ban preserves scheduling pending with sufficient post ban window`() {
        val setup = createConnectionInSchedulingPending()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        connectionRepository.updateSchedulingExpiresAt(
            connectionId = setup.connectionId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin().plusSeconds(1)
        )

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(ConnectionState.SCHEDULING_PENDING, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertNull(schedulingService.findNegotiationOrNull(setup.connectionId))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    fun `temporary ban preserves visual approved match when downstream connection remains viable`() {
        val setup = createConnectionInSchedulingPending()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        connectionRepository.updateSchedulingExpiresAt(
            connectionId = setup.connectionId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin()
        )

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(MatchState.VISUAL_APPROVED, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(ConnectionState.SCHEDULING_PENDING, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    fun `temporary ban preserves scheduling phase with sufficient post ban window`() {
        val setup = createConnectionInSchedulingPhase()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        connectionRepository.updateSchedulingExpiresAt(
            connectionId = setup.connectionId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin().plusSeconds(1)
        )

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(NegotiationStatus.PENDING, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    fun `temporary ban contains scheduling with less than resume margin`() {
        val setup = createConnectionInSchedulingPhase()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        val reliabilityEventsBefore = reliabilityEventCountFor(setup.userAId, setup.userBId)
        connectionRepository.updateSchedulingExpiresAt(
            connectionId = setup.connectionId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin().minusSeconds(1)
        )

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(NegotiationStatus.FAILED, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
        assertEquals(reliabilityEventsBefore, reliabilityEventCountFor(setup.userAId, setup.userBId))
    }

    @Test
    fun `temporary ban preserves scheduling at exact resume margin`() {
        val setup = createConnectionInSchedulingPhase()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        connectionRepository.updateSchedulingExpiresAt(
            connectionId = setup.connectionId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin()
        )

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(NegotiationStatus.PENDING, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
    }

    @Test
    fun `temporary ban preserves scheduled second chat at exact entry cutoff margin`() {
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        val confirmedDateTime =
            banExpiresAt.plusTemporaryResumeMargin().minusMinutes(secondChatEntryWindowMinutes)
        val setup = createSecondChatScheduledAt(confirmedDateTime)

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(ConnectionState.SECOND_CHAT_SCHEDULED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(NegotiationStatus.CONFIRMED, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    fun `temporary ban closes scheduled second chat one instant beyond entry cutoff margin`() {
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1).plusSeconds(1)
        val confirmedDateTime =
            now.plusHours(1).plusTemporaryResumeMargin().minusMinutes(secondChatEntryWindowMinutes)
        val setup = createSecondChatScheduledAt(confirmedDateTime)

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `temporary ban preserves available second chat with sufficient entry window`() {
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        val confirmedDateTime =
            banExpiresAt.plusTemporaryResumeMargin().minusMinutes(secondChatEntryWindowMinutes)
        val setup = createSecondChatAvailableAt(confirmedDateTime)

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(ConnectionState.SECOND_CHAT_AVAILABLE, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    fun `temporary ban closes available second chat with insufficient entry window`() {
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1).plusSeconds(1)
        val confirmedDateTime =
            now.plusHours(1).plusTemporaryResumeMargin().minusMinutes(secondChatEntryWindowMinutes)
        val setup = createSecondChatAvailableAt(confirmedDateTime)

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `temporary ban during active second chat cancels chat and closes connection`() {
        val setup = createActiveSecondChat()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        val reliabilityEventsBefore = reliabilityEventCountFor(setup.userAId, setup.userBId)

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)
        val secondChat = chatRepository.findById(setup.secondChatId).orElseThrow()

        assertEquals(ChatStatus.CANCELLED, secondChat.status)
        assertEquals(ChatEndReason.USER_BANNED, secondChat.endedReason)
        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
        assertEquals(reliabilityEventsBefore, reliabilityEventCountFor(setup.userAId, setup.userBId))
    }

    @Test
    fun `temporary ban removes queue entry even when long lived engagement is preserved`() {
        val setup = createMatchInVisualPhase()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin()
        )
        enqueueForMatchmaking(setup.userBId)

        createTemporaryPenalty(setup.userBId, now, banExpiresAt)

        assertFalse(matchmakingQueueRepository.existsByUserId(setup.userBId))
        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)
    }

    @Test
    fun `existing longer temporary ban controls new shorter temporary containment`() {
        val setup = createMatchInVisualPhase()
        val now = fixedDecisionTime()
        val longerExpiresAt = now.plusHours(3)
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = setup.userBId,
                reason = "Existing longer temporary violation",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = longerExpiresAt,
                active = true
            )
        )
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = longerExpiresAt.plusTemporaryResumeMargin()
        )

        createTemporaryPenalty(setup.userBId, now, now.plusHours(1))

        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(longerExpiresAt.toInstant(), penaltyService.resolveEffectiveBan(setup.userBId, now)?.expiresAt?.toInstant())
    }

    @Test
    fun `new longer temporary ban reevaluates surviving engagement against later expiry`() {
        val setup = createMatchInVisualPhase()
        val now = fixedDecisionTime()
        val shorterExpiresAt = now.plusHours(1)
        val longerExpiresAt = now.plusHours(2)
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = setup.userBId,
                reason = "Existing shorter temporary violation",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = shorterExpiresAt,
                active = true
            )
        )
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = shorterExpiresAt.plusTemporaryResumeMargin()
        )

        createTemporaryPenalty(setup.userBId, now, longerExpiresAt)

        assertEquals(MatchState.VISUAL_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(longerExpiresAt.toInstant(), penaltyService.resolveEffectiveBan(setup.userBId, now)?.expiresAt?.toInstant())
    }

    @Test
    fun `effective permanent ban causes full containment when new penalty is temporary`() {
        val setup = createMatchInVisualPhase()
        val now = fixedDecisionTime()
        penaltyRepository.saveAndFlush(
            Penalty(
                userId = setup.userBId,
                reason = "Existing permanent violation",
                type = PenaltyType.PERMANENT_BAN,
                expiresAt = null,
                active = true
            )
        )
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = now.plusDays(2)
        )

        createTemporaryPenalty(setup.userBId, now, now.plusHours(1))

        assertEquals(PenaltyType.PERMANENT_BAN, penaltyService.resolveEffectiveBan(setup.userBId, now)?.type)
        assertEquals(MatchState.VISUAL_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `temporary ban expiry does not reopen closed or mutate preserved engagements`() {
        val closed = createMatchWithFirstChat("ban-expiry-closed")
        val preserved = createMatchInVisualPhase()
        val now = fixedDecisionTime()
        val banExpiresAt = now.plusHours(1)
        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = preserved.matchId,
            expiresAt = banExpiresAt.plusTemporaryResumeMargin()
        )

        createTemporaryPenalty(closed.userBId, now, banExpiresAt)
        createTemporaryPenalty(preserved.userBId, now, banExpiresAt)

        assertNull(penaltyService.resolveEffectiveBan(closed.userBId, banExpiresAt))
        assertNull(penaltyService.resolveEffectiveBan(preserved.userBId, banExpiresAt))
        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(closed.matchId).state)
        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(closed.firstChatId).orElseThrow().status)
        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(preserved.matchId).state)
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

    private fun createTemporaryPenalty(
        userId: UUID,
        now: OffsetDateTime,
        expiresAt: OffsetDateTime
    ) {
        penaltyService.createTemporaryPenalty(
            userId = userId,
            reason = "Confirmed temporary violation",
            duration = Duration.between(now, expiresAt),
            now = now
        )
    }

    private fun fixedDecisionTime(): OffsetDateTime =
        OffsetDateTime.parse("2026-09-01T12:00:00Z")

    private fun OffsetDateTime.plusTemporaryResumeMargin(): OffsetDateTime =
        plusMinutes(temporaryResumeMarginMinutes)

    private fun createConnectionInSchedulingPending(): ConnectionFixture {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)
        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")

        return ConnectionFixture(
            userAId = setup.userAId,
            userBId = setup.userBId,
            matchId = setup.matchId,
            connectionId = connection.id
        )
    }

    private fun createSecondChatScheduledAt(confirmedDateTime: OffsetDateTime): ConnectionFixture {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot(),
            expectedRoundNumber = 1
        )
        schedulingService.acceptProposal(
            connectionId = setup.connectionId,
            proposalId = proposal.id,
            acceptorUserId = setup.userBId
        )
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = confirmedDateTime
        )

        return setup
    }

    private fun createSecondChatAvailableAt(confirmedDateTime: OffsetDateTime): ConnectionFixture {
        val setup = createSecondChatScheduledAt(confirmedDateTime)
        val connection = connectionService.findByIdOrThrow(setup.connectionId)
        connection.state = ConnectionState.SECOND_CHAT_AVAILABLE
        connectionRepository.saveAndFlush(connection)
        return setup
    }
}
