package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.MatchState
import com.reals.backend.integration.BaseIT
import com.reals.backend.scheduler.InactivityCheckJob
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

class FirstChatDecisionOnlyIntegrationTest : BaseIT() {

    @Test
    fun `partner approval leaves chat active and pending participant can approve into visual phase`() {
        val setup = createMatchWithFirstChat("decision-only-approve")

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(setup.firstChatId).orElseThrow().status)
        assertEquals(MatchState.CHAT_ACTIVE, matchRepository.findById(setup.matchId).orElseThrow().state)
        assertEquals(ChatContinueDecision.APPROVED, chatDecisionRepository.findByChatId(setup.firstChatId)?.userADecision)
        assertNull(chatDecisionRepository.findByChatId(setup.firstChatId)?.userBDecision)

        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        assertEquals(ChatStatus.FINISHED, chat.status)
        assertEquals(ChatEndReason.SYSTEM_CLOSED, chat.endedReason)
        assertEquals(MatchState.VISUAL_PHASE, matchRepository.findById(setup.matchId).orElseThrow().state)
        assertNotNull(visualReviewRepository.findByMatchId(setup.matchId))
    }

    @Test
    fun `pending participant rejection after partner approval persists decision mismatch without cancellation artifacts`() {
        val setup = createMatchWithFirstChat("decision-only-reject")

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.REJECTED)

        val decision = chatDecisionRepository.findByChatId(setup.firstChatId) ?: error("Expected chat decision")
        assertEquals(ChatContinueDecision.APPROVED, decision.userADecision)
        assertEquals(ChatContinueDecision.REJECTED, decision.userBDecision)

        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        assertEquals(ChatStatus.FINISHED, chat.status)
        assertEquals(ChatEndReason.FIRST_CHAT_DECISION_MISMATCH, chat.endedReason)
        assertEquals(MatchState.CHAT_REJECTED, matchRepository.findById(setup.matchId).orElseThrow().state)
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.MATCH))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.MATCH))
        assertTrue(chatExitRequestRepository.findByChatIdOrderByCreatedAtDesc(setup.firstChatId).isEmpty())
        assertEquals(0, penaltyRepository.count())
    }

    @Test
    fun `rejection while partner is pending keeps unilateral cancellation semantics`() {
        val setup = createMatchWithFirstChat("decision-only-normal-reject")

        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.REJECTED)

        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        assertEquals(ChatStatus.CANCELLED, chat.status)
        assertEquals(ChatEndReason.UNILATERAL_CANCEL, chat.endedReason)
        assertEquals(MatchState.CHAT_REJECTED, matchRepository.findById(setup.matchId).orElseThrow().state)
        val exitRequest = chatExitRequestRepository.findByChatIdOrderByCreatedAtDesc(setup.firstChatId).single()
        assertEquals(ChatExitRequestType.UNILATERAL_CANCEL, exitRequest.type)
        assertEquals(ChatExitRequestStatus.ACCEPTED, exitRequest.status)
    }

    @Test
    fun `decision only state rejects ordinary first chat conversation and exit mutations`() {
        val setup = createMatchWithFirstChat("decision-only-rejections")
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

        assertOrdinaryFirstChatMutationsRejected(setup, setup.userBId)
        assertOrdinaryFirstChatMutationsRejected(setup, setup.userAId)

        assertTrue(chatService.getMessages(setup.firstChatId, setup.userAId).isEmpty())
        assertTrue(chatService.getMessages(setup.firstChatId, setup.userBId).isEmpty())
        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(setup.firstChatId).orElseThrow().status)
        assertTrue(chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.firstChatId).isEmpty())
        assertTrue(chatExitRequestRepository.findByChatIdOrderByCreatedAtDesc(setup.firstChatId).isEmpty())
    }

    @Test
    fun `already approved participant cannot replace final decision while pending participant can still decide`() {
        val setup = createMatchWithFirstChat("decision-only-already-approved")
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

        val exception =
            assertThrows<DomainConflictException> {
                chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.REJECTED)
            }

        assertEquals(DomainErrorCode.CHAT_DECISION_ALREADY_SUBMITTED, exception.code)

        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.REJECTED)

        val decision = chatDecisionRepository.findByChatId(setup.firstChatId) ?: error("Expected chat decision")
        assertEquals(ChatContinueDecision.APPROVED, decision.userADecision)
        assertEquals(ChatContinueDecision.REJECTED, decision.userBDecision)
        assertEquals(
            ChatEndReason.FIRST_CHAT_DECISION_MISMATCH,
            chatRepository.findById(setup.firstChatId).orElseThrow().endedReason
        )
    }

    @Test
    fun `decision only disables first chat inactivity timeout but keeps ordinary mutation frozen`() {
        val setup = createMatchWithFirstChat("decision-only-inactivity-disabled")
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        moveFirstChatStartIntoPast(setup.firstChatId, minutes = 6)

        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        assertNull(chatService.inactivityExpiresAt(chat))
        assertFalse(chatService.findInactiveChatIds(OffsetDateTime.now(), limit = 10).contains(setup.firstChatId))

        assertDecisionOnlyConflict {
            chatService.sendMessage(setup.firstChatId, setup.userBId, "No debería enviarse")
        }
        assertDecisionOnlyConflict {
            chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
        }

        assertFalse(
            chatService.endChat(
                chatId = setup.firstChatId,
                finalStatus = ChatStatus.ABANDONED,
                endedReason = ChatEndReason.INACTIVITY_TIMEOUT
            )
        )

        val summary =
            InactivityCheckJob(
                chatAccessService = chatAccessService,
                chatLifecycleService = chatLifecycleService,
                chatMessageRepository = chatMessageRepository,
                connectionRepository = connectionRepository,
                inactivityThresholdMinutes = 5,
                batchSize = 10
            ).processInactiveChats()

        assertEquals(0, summary.succeeded)
        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(setup.firstChatId).orElseThrow().status)
        assertNull(chatRepository.findById(setup.firstChatId).orElseThrow().endedReason)
    }

    @Test
    fun `normal first chat inactivity still abandons active pending chat`() {
        val setup = createMatchWithFirstChat("decision-only-normal-inactivity")
        moveFirstChatStartIntoPast(setup.firstChatId, minutes = 6)

        val exception =
            assertThrows<DomainConflictException> {
                chatService.sendMessage(setup.firstChatId, setup.userAId, "Late message")
            }

        assertEquals(DomainErrorCode.CHAT_ABANDONED, exception.code)
        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        assertEquals(ChatStatus.ABANDONED, chat.status)
        assertEquals(ChatEndReason.INACTIVITY_TIMEOUT, chat.endedReason)
    }

    @Test
    fun `decision only state still permits safety report cancellation`() {
        listOf(
            "pending-reporter" to { setup: MatchFixture -> setup.userBId },
            "approved-reporter" to { setup: MatchFixture -> setup.userAId }
        ).forEachIndexed { index, (suffix, reporter) ->
            val setup = createMatchWithFirstChat("decision-only-safety-$suffix")
            chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

            chatExitService.cancelChatForSafety(
                chatId = setup.firstChatId,
                reporterUserId = reporter(setup),
                reason = ChatExitReason.INAPPROPRIATE_BEHAVIOR,
                details = "Safety report details"
            )

            val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
            assertEquals(ChatStatus.CANCELLED, chat.status)
            assertEquals(ChatEndReason.SAFETY_REPORT, chat.endedReason)
            assertEquals((index + 1).toLong(), safetyReportRepository.count())
        }
    }

    @Test
    fun `ordinary exit after committed partner approval observes decision only state`() {
        val setup = createMatchWithFirstChat("decision-only-serialization-approval-first")
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

        assertDecisionOnlyConflict {
            chatExitService.cancelChatUnilaterally(setup.firstChatId, setup.userBId)
        }

        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(setup.firstChatId).orElseThrow().status)
        assertTrue(chatExitRequestRepository.findByChatIdOrderByCreatedAtDesc(setup.firstChatId).isEmpty())
        assertEquals(MatchState.CHAT_ACTIVE, matchRepository.findById(setup.matchId).orElseThrow().state)
    }

    @Test
    fun `mutual exit request winning first blocks later approval through existing pending request rule`() {
        val setup = createMatchWithFirstChat("decision-only-serialization-mutual-first")
        val exitRequest = chatExitService.requestMutualCancellation(setup.firstChatId, setup.userBId)

        val exception =
            assertThrows<DomainConflictException> {
                chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
            }

        assertEquals(DomainErrorCode.CHAT_MUTUAL_CANCELLATION_PENDING, exception.code)
        assertEquals(ChatExitRequestStatus.PENDING, chatExitRequestRepository.findById(exitRequest.id).orElseThrow().status)
        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(setup.firstChatId).orElseThrow().status)
        assertNull(chatDecisionRepository.findByChatId(setup.firstChatId)?.userADecision)
    }

    @Test
    fun `first chat expiration after one approval keeps existing terminal path`() {
        val setup = createMatchWithFirstChat("decision-only-expiration")
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

        chatService.endChat(
            chatId = setup.firstChatId,
            finalStatus = ChatStatus.EXPIRED,
            endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
        )

        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        assertEquals(ChatStatus.EXPIRED, chat.status)
        assertEquals(ChatEndReason.ABSOLUTE_TIMEOUT, chat.endedReason)
        assertEquals(MatchState.EXPIRED, matchRepository.findById(setup.matchId).orElseThrow().state)
    }

    private fun assertOrdinaryFirstChatMutationsRejected(
        setup: MatchFixture,
        userId: UUID
    ) {
        assertDecisionOnlyConflict {
            chatService.sendMessage(setup.firstChatId, userId, "No debería enviarse")
        }
        assertDecisionOnlyConflict {
            chatService.preflightNewAudioMessage(setup.firstChatId, userId)
        }
        assertDecisionOnlyConflict {
            chatService.requestFirstChatGuidanceNext(setup.firstChatId, userId)
        }
        assertDecisionOnlyConflict {
            chatExitService.requestMutualCancellation(setup.firstChatId, userId)
        }
        assertDecisionOnlyConflict {
            chatExitService.cancelChatUnilaterally(setup.firstChatId, userId)
        }
    }

    private fun assertDecisionOnlyConflict(action: () -> Unit) {
        val exception = assertThrows<DomainConflictException> { action() }
        assertEquals(DomainErrorCode.FIRST_CHAT_DECISION_ONLY, exception.code)
    }

    private fun moveFirstChatStartIntoPast(
        chatId: UUID,
        minutes: Long
    ) {
        val chat = chatRepository.findById(chatId).orElseThrow()
        chat.startedAt = OffsetDateTime.now().minusMinutes(minutes)
        chatRepository.saveAndFlush(chat)
    }
}
