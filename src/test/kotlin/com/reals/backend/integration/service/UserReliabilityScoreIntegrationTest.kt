package com.reals.backend.integration.service

import com.reals.backend.controller.dto.CreateSafetyReportRequest
import com.reals.backend.controller.dev.DevUserReliabilityController
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.UserReliabilityDimension
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.scheduler.UserReliabilityEventCleanupJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import java.util.UUID

@TestPropertySource(
    properties = [
        "user-reliability.enabled=true",
        "user-reliability.first-chat.min-participation-messages-per-user=2",
        "user-reliability.first-chat.min-participation-minutes=5",
        "chat.second-chat.on-time-window-minutes=10",
        "chat.second-chat.entry-window-minutes=20",
        "chat.second-chat.no-show-claim-countdown-seconds=60",
        "user-reliability.matchmaking.max-modifier=0.05"
    ]
)
class UserReliabilityScoreIntegrationTest : BaseIT() {

    @Test
    fun `mutual positive first chat resolution creates events for both users`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        assertSingleEvent(setup.userAId, UserReliabilityEventType.FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION, 2)
        assertSingleEvent(setup.userBId, UserReliabilityEventType.FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION, 2)
    }

    @Test
    fun `mutual no spark closure creates events for both users`() {
        val setup = createMatchWithFirstChat()
        val request = chatExitService.requestMutualCancellation(setup.firstChatId, setup.userAId)

        chatExitService.acceptMutualCancellation(setup.firstChatId, request.id, setup.userBId)

        assertSingleEvent(setup.userAId, UserReliabilityEventType.FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE, 2)
        assertSingleEvent(setup.userBId, UserReliabilityEventType.FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE, 2)
    }

    @Test
    fun `early unilateral first chat close creates event for closer`() {
        val setup = createMatchWithFirstChat()

        chatExitService.cancelChatUnilaterally(setup.firstChatId, setup.userAId)

        assertSingleEvent(setup.userAId, UserReliabilityEventType.FIRST_CHAT_EARLY_UNILATERAL_CLOSE, -2)
        assertNoEvents(setup.userBId)
    }

    @Test
    fun `unilateral first chat close after minimum participation creates light event for closer`() {
        val setup = createMatchWithFirstChat()
        repeat(2) { index ->
            chatService.sendMessage(setup.firstChatId, setup.userAId, "A message $index")
            chatService.sendMessage(setup.firstChatId, setup.userBId, "B message $index")
        }
        moveFirstChatStartIntoPast(setup.firstChatId, minutes = 6)

        chatExitService.cancelChatUnilaterally(setup.firstChatId, setup.userAId)

        assertSingleEvent(
            setup.userAId,
            UserReliabilityEventType.FIRST_CHAT_UNILATERAL_CLOSE_AFTER_MINIMUM_PARTICIPATION,
            -1
        )
    }

    @Test
    fun `counterparty inactive close creates event only for inactive counterpart`() {
        val setup = createMatchWithFirstChat()
        chatService.sendMessage(setup.firstChatId, setup.userAId, "I am here")
        moveFirstChatStartIntoPast(setup.firstChatId, minutes = 6)

        chatExitService.cancelChatUnilaterally(setup.firstChatId, setup.userAId)

        assertNoEvents(setup.userAId)
        assertSingleEvent(
            setup.userBId,
            UserReliabilityEventType.FIRST_CHAT_CLOSED_AFTER_COUNTERPARTY_INACTIVE,
            -2
        )
    }

    @Test
    fun `mutual close request timeout creates event for ignoring user only`() {
        val setup = createMatchWithFirstChat()
        val request = chatExitService.requestMutualCancellation(setup.firstChatId, setup.userAId)
        request.createdAt = OffsetDateTime.now().minusSeconds(30)
        chatExitRequestRepository.save(request)

        chatExitService.timeoutMutualCancellation(setup.firstChatId, request.id, setup.userAId)

        assertNoEvents(setup.userAId)
        assertSingleEvent(setup.userBId, UserReliabilityEventType.FIRST_CHAT_MUTUAL_CLOSE_REQUEST_IGNORED, -2)
    }

    @Test
    fun `first chat expiration creates event for unresolved user`() {
        val setup = createMatchWithFirstChat()
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

        chatService.endChat(
            chatId = setup.firstChatId,
            finalStatus = ChatStatus.EXPIRED,
            endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
        )

        assertNoEvents(setup.userAId)
        assertSingleEvent(setup.userBId, UserReliabilityEventType.FIRST_CHAT_EXPIRED_NO_DECISION, -3)
    }

    @Test
    fun `visual review expiration creates event for unresolved user and visual decisions stay neutral`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewRepository.updateExpiresAtByMatchId(setup.matchId, OffsetDateTime.now().minusSeconds(1))

        visualReviewService.expireVisualReview(setup.matchId)

        assertNoEvent(setup.userAId, UserReliabilityEventType.VISUAL_REVIEW_EXPIRED_NO_DECISION)
        assertSingleEvent(setup.userBId, UserReliabilityEventType.VISUAL_REVIEW_EXPIRED_NO_DECISION, -2)
    }

    @Test
    fun `visual personal message submission creates participation event for submitting user`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Me gustaria seguir conversando")

        val event = events(setup.userAId, UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED).single()
        assertEquals(UserReliabilityDimension.ConversationParticipationScore, event.dimension)
        assertEquals(1, event.delta)
        assertEquals(setup.matchId, event.relatedMatchId)
        assertNoEvent(setup.userBId, UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED)

        val breakdownEvent = userReliabilityScoreService
            .scoreBreakdown(setup.userAId)
            .events
            .single { it.event.eventType == UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED }
        assertEquals(1, breakdownEvent.event.delta)
        assertEquals(1.0, breakdownEvent.temporalWeight)
        assertEquals(1.0, breakdownEvent.effectiveDelta)
    }

    @Test
    fun `both users can independently receive visual personal message participation event`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Mensaje A")
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")

        assertSingleEvent(setup.userAId, UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED, 1)
        assertSingleEvent(setup.userBId, UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED, 1)
        assertEquals(
            2,
            userReliabilityEventRepository.findAll().count {
                it.eventType == UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED &&
                    it.relatedMatchId == setup.matchId
            }
        )
    }

    @Test
    fun `reading partner personal message creates no reliability event`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")
        val eventCountBeforeRead = userReliabilityEventRepository.count()

        assertEquals("Mensaje B", visualReviewService.getPartnerMessage(setup.matchId, setup.userAId))

        assertEquals(eventCountBeforeRead, userReliabilityEventRepository.count())
        assertNoEvent(setup.userAId, UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED)
        assertSingleEvent(setup.userBId, UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED, 1)
    }

    @Test
    fun `failed visual personal message submission creates no participation event`() {
        val setup = createMatchInVisualPhase()

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "   ")
        }

        assertNoEvent(setup.userAId, UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED)
        assertNoEvent(setup.userBId, UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED)
    }

    @Test
    fun `duplicate visual personal message submission does not create second participation event`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Primer mensaje")

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Segundo mensaje")
        }

        assertEquals("Primer mensaje", visualReviewService.findByMatchIdOrThrow(setup.matchId).personalMessageA)
        assertSingleEvent(setup.userAId, UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED, 1)
    }

    @Test
    fun `scheduling proposal and expiration create expected events`() {
        val setup = createConnectionInSchedulingPhase()

        schedulingService.addProposal(setup.connectionId, setup.userAId, futureHalfHourSlot(), 1)
        schedulingService.expireNegotiation(setup.connectionId)

        assertSingleEvent(setup.userAId, UserReliabilityEventType.SCHEDULING_SLOTS_PROPOSED_ON_TIME, 1)
        assertNoEvent(setup.userAId, UserReliabilityEventType.SCHEDULING_EXPIRED_NO_PROPOSAL)
        assertSingleEvent(setup.userBId, UserReliabilityEventType.SCHEDULING_EXPIRED_NO_PROPOSAL, -3)
    }

    @Test
    fun `second chat attendance and no show use explicit join only`() {
        val attended = createScheduledSecondChatReadyToEnter()
        val attendedAt = OffsetDateTime.now().plusHours(1).withNano(0)
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = attended.connectionId,
            confirmedDateTime = attendedAt
        )
        val attendedJoin =
            joinSecondChatOrThrow(
                connectionId = attended.connectionId,
                userId = attended.userAId,
                now = attendedAt
            )
        chatService.sendMessage(attendedJoin.chatId!!, attended.userAId, "Message is not attendance")

        assertSingleEvent(attended.userAId, UserReliabilityEventType.SECOND_CHAT_CONFIRMED_ATTENDED, 4)

        val late = createScheduledSecondChatReadyToEnter()
        val lateAt = OffsetDateTime.now().plusHours(2).withNano(0)
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = late.connectionId,
            confirmedDateTime = lateAt
        )
        joinSecondChatOrThrow(
            connectionId = late.connectionId,
            userId = late.userAId,
            now = lateAt.plusMinutes(10)
        )
        joinSecondChatOrThrow(
            connectionId = late.connectionId,
            userId = late.userAId,
            now = lateAt.plusMinutes(11)
        )

        assertSingleEvent(late.userAId, UserReliabilityEventType.SECOND_CHAT_LATE_ARRIVAL, -2)
        assertNoEvent(late.userAId, UserReliabilityEventType.SECOND_CHAT_CONFIRMED_ATTENDED)

        val noShow = createScheduledSecondChatReadyToEnter()
        val noShowAt = OffsetDateTime.now().plusHours(3).withNano(0)
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = noShow.connectionId,
            confirmedDateTime = noShowAt
        )

        secondChatLifecycleService.resolveHardCutoffNoShow(
            connectionId = noShow.connectionId,
            now = noShowAt.plusMinutes(20)
        )
        secondChatLifecycleService.resolveHardCutoffNoShow(
            connectionId = noShow.connectionId,
            now = noShowAt.plusMinutes(21)
        )

        assertSingleEvent(noShow.userAId, UserReliabilityEventType.SECOND_CHAT_NO_SHOW, -10)
        assertSingleEvent(noShow.userBId, UserReliabilityEventType.SECOND_CHAT_NO_SHOW, -10)
    }

    @Test
    fun `abusive safety report resolution creates event and ordinary dismissal does not`() {
        val abusive = createUserSafetyReport("abusive-report")
        val neutral = createUserSafetyReport("neutral-report")
        val admin = userService.createUser("admin-reliability-${UUID.randomUUID()}@example.com")

        safetyReportService.dismissAbusiveOrUnjustifiedReport(
            reportId = abusive.reportId,
            adminUserId = admin.id,
            notes = "Reporter acknowledged fabricated report"
        )
        safetyReportService.dismissReport(
            reportId = neutral.reportId,
            adminUserId = admin.id,
            notes = "Insufficient evidence"
        )

        assertSingleEvent(abusive.reporterUserId, UserReliabilityEventType.SAFETY_REPORT_DETERMINED_ABUSIVE, -8)
        assertNoEvent(neutral.reporterUserId, UserReliabilityEventType.SAFETY_REPORT_DETERMINED_ABUSIVE)
    }

    @Test
    fun `effective score weights active events and cleanup deletes expired events`() {
        val user = userService.createUser("score-reliability-${UUID.randomUUID()}@example.com")
        val now = OffsetDateTime.now()

        userReliabilityScoreService.recordEvent(
            userId = user.id,
            eventType = UserReliabilityEventType.FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION,
            relatedMatchId = UUID.randomUUID(),
            occurredAt = now.minusDays(1)
        )
        userReliabilityScoreService.recordEvent(
            userId = user.id,
            eventType = UserReliabilityEventType.FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE,
            relatedMatchId = UUID.randomUUID(),
            occurredAt = now.minusDays(10)
        )
        userReliabilityScoreService.recordEvent(
            userId = user.id,
            eventType = UserReliabilityEventType.FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION,
            relatedMatchId = UUID.randomUUID(),
            occurredAt = now.minusDays(20)
        )

        assertEquals(103.0, userReliabilityScoreService.effectiveScore(user.id, now))

        UserReliabilityEventCleanupJob(userReliabilityScoreService).processExpiredEvents()

        assertEquals(
            2,
            userReliabilityEventRepository.findAll().count { it.userId == user.id }
        )
    }

    @Test
    fun `duplicate reliability writes and matchmaking modifier are bounded`() {
        val setup = createMatchWithFirstChat()

        repeat(2) {
            userReliabilityScoreService.recordEvent(
                userId = setup.userAId,
                eventType = UserReliabilityEventType.FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION,
                relatedMatchId = setup.matchId,
                relatedChatId = setup.firstChatId
            )
        }

        assertSingleEvent(setup.userAId, UserReliabilityEventType.FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION, 2)
        assertTrue(userReliabilityScoreService.matchmakingModifierForScores(500.0, 500.0) <= 0.05)
        assertTrue(userReliabilityScoreService.matchmakingModifierForScores(-500.0, -500.0) >= -0.05)
    }

    @Test
    fun `debug endpoint response returns weighted score breakdown without mutation`() {
        val user = userService.createUser("debug-enabled-${UUID.randomUUID()}@example.com")
        val now = OffsetDateTime.now()

        userReliabilityScoreService.recordEvent(
            userId = user.id,
            eventType = UserReliabilityEventType.FIRST_CHAT_EARLY_UNILATERAL_CLOSE,
            relatedMatchId = UUID.randomUUID(),
            occurredAt = now.minusDays(1)
        )
        userReliabilityScoreService.recordEvent(
            userId = user.id,
            eventType = UserReliabilityEventType.SCHEDULING_EXPIRED_NO_PROPOSAL,
            relatedConnectionId = UUID.randomUUID(),
            occurredAt = now.minusDays(10)
        )
        val beforeCount = userReliabilityEventRepository.count()
        val controller = DevUserReliabilityController(
            userService = userService,
            userReliabilityScoreService = userReliabilityScoreService
        )

        val response = controller.getUserReliability(user.id).body ?: error("Expected response body")

        assertEquals(user.id, response.userId)
        assertTrue(response.enabled)
        assertEquals(100, response.baseScore)
        assertEquals(-3.5, response.weightedDelta)
        assertEquals(96.5, response.effectiveScore)
        assertEquals(2, response.events.size)
        assertEquals(beforeCount, userReliabilityEventRepository.count())

        val fullWeightEvent =
            response.events.single { it.eventType == UserReliabilityEventType.FIRST_CHAT_EARLY_UNILATERAL_CLOSE }
        assertEquals(-2, fullWeightEvent.delta)
        assertEquals(1.0, fullWeightEvent.temporalWeight)
        assertEquals(-2.0, fullWeightEvent.effectiveDelta)

        val halfWeightEvent =
            response.events.single { it.eventType == UserReliabilityEventType.SCHEDULING_EXPIRED_NO_PROPOSAL }
        assertEquals(-3, halfWeightEvent.delta)
        assertEquals(0.5, halfWeightEvent.temporalWeight)
        assertEquals(-1.5, halfWeightEvent.effectiveDelta)
    }

    private fun moveFirstChatStartIntoPast(
        chatId: UUID,
        minutes: Long
    ) {
        val chat = chatRepository.findById(chatId).orElseThrow()
        chat.startedAt = OffsetDateTime.now().minusMinutes(minutes)
        chatRepository.saveAndFlush(chat)
    }

    private fun createUserSafetyReport(prefix: String): SafetyReportFixture {
        val setup = createMatchInVisualPhase()
        val report =
            safetyReportService.createUserReport(
                reporterUserId = setup.userAId,
                request = CreateSafetyReportRequest(
                    reportedUserId = setup.userBId,
                    contextType = SafetyReportContextType.VISUAL_PROFILE,
                    matchId = setup.matchId,
                    reason = SafetyReportReason.OTHER,
                    details = "$prefix details"
                )
            ).report

        return SafetyReportFixture(
            reportId = report.id,
            reporterUserId = setup.userAId
        )
    }

    private fun assertSingleEvent(
        userId: UUID,
        eventType: UserReliabilityEventType,
        delta: Int
    ) {
        val events = events(userId, eventType)
        assertEquals(1, events.size)
        assertEquals(delta, events.single().delta)
    }

    private fun assertNoEvent(
        userId: UUID,
        eventType: UserReliabilityEventType
    ) {
        assertEquals(0, events(userId, eventType).size)
    }

    private fun assertNoEvents(userId: UUID) {
        assertEquals(0, userReliabilityEventRepository.findAll().count { it.userId == userId })
    }

    private fun events(
        userId: UUID,
        eventType: UserReliabilityEventType
    ) =
        userReliabilityEventRepository.findAll()
            .filter { it.userId == userId && it.eventType == eventType }

    private data class SafetyReportFixture(
        val reportId: UUID,
        val reporterUserId: UUID
    )
}
