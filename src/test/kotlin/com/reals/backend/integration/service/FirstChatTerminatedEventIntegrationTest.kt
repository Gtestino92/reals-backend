package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.AccountDeletionService
import com.reals.backend.service.FirstChatTerminatedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.time.OffsetDateTime
import java.util.UUID

@RecordApplicationEvents
class FirstChatTerminatedEventIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var applicationEvents: ApplicationEvents

    @Autowired
    private lateinit var accountDeletionService: AccountDeletionService

    @Test
    fun `pending mutual cancellation request does not publish first chat terminated event`() {
        val setup = createMatchWithFirstChat("first-chat-event-pending-mutual")

        assertFirstChatTerminatedEvents(0) {
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )
        }
    }

    @Test
    fun `mutual cancellation accepted rejected and timed out publish once each`() {
        val accepted = createMatchWithFirstChat("first-chat-event-accepted")
        val acceptedRequest =
            chatExitService.requestMutualCancellation(
                chatId = accepted.firstChatId,
                requesterUserId = accepted.userAId
            )
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.CANCELLED,
            expectedReason = ChatEndReason.MUTUAL_CANCEL
        ) {
            chatExitService.acceptMutualCancellation(
                chatId = accepted.firstChatId,
                requestId = acceptedRequest.id,
                responderUserId = accepted.userBId
            )
        }

        val rejected = createMatchWithFirstChat("first-chat-event-rejected")
        val rejectedRequest =
            chatExitService.requestMutualCancellation(
                chatId = rejected.firstChatId,
                requesterUserId = rejected.userAId
            )
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.CANCELLED,
            expectedReason = ChatEndReason.MUTUAL_CANCEL
        ) {
            chatExitService.rejectMutualCancellation(
                chatId = rejected.firstChatId,
                requestId = rejectedRequest.id,
                responderUserId = rejected.userBId
            )
        }

        val timedOut = createMatchWithFirstChat("first-chat-event-timeout")
        val timedOutRequest =
            expired(
                chatExitService.requestMutualCancellation(
                    chatId = timedOut.firstChatId,
                    requesterUserId = timedOut.userAId
                )
            )
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.CANCELLED,
            expectedReason = ChatEndReason.MUTUAL_CANCEL
        ) {
            chatExitService.timeoutMutualCancellation(
                chatId = timedOut.firstChatId,
                requestId = timedOutRequest.id,
                userId = timedOut.userBId
            )
        }
    }

    @Test
    fun `unilateral safety and positive first chat completion publish once each`() {
        val unilateral = createMatchWithFirstChat("first-chat-event-unilateral")
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.CANCELLED,
            expectedReason = ChatEndReason.UNILATERAL_CANCEL
        ) {
            chatExitService.cancelChatUnilaterally(
                chatId = unilateral.firstChatId,
                userId = unilateral.userAId
            )
        }

        val safety = createMatchWithFirstChat("first-chat-event-safety")
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.CANCELLED,
            expectedReason = ChatEndReason.SAFETY_REPORT
        ) {
            chatExitService.cancelChatForSafety(
                chatId = safety.firstChatId,
                reporterUserId = safety.userAId,
                details = "Safety report details"
            )
        }

        val positive = createMatchWithFirstChat("first-chat-event-positive")
        chatService.recordChatDecision(positive.matchId, positive.userAId, ChatContinueDecision.APPROVED)
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.FINISHED,
            expectedReason = ChatEndReason.SYSTEM_CLOSED
        ) {
            chatService.recordChatDecision(positive.matchId, positive.userBId, ChatContinueDecision.APPROVED)
        }

        val mismatch = createMatchWithFirstChat("first-chat-event-mismatch")
        chatService.recordChatDecision(mismatch.matchId, mismatch.userAId, ChatContinueDecision.APPROVED)
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.FINISHED,
            expectedReason = ChatEndReason.FIRST_CHAT_DECISION_MISMATCH
        ) {
            chatService.recordChatDecision(mismatch.matchId, mismatch.userBId, ChatContinueDecision.REJECTED)
        }
    }

    @Test
    fun `absolute timeout inactivity and idempotent no-op publish only actual first chat transitions`() {
        val expired = createMatchWithFirstChat("first-chat-event-expired")
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.EXPIRED,
            expectedReason = ChatEndReason.ABSOLUTE_TIMEOUT
        ) {
            chatService.endChat(
                chatId = expired.firstChatId,
                finalStatus = ChatStatus.EXPIRED,
                endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
            )
        }
        assertFirstChatTerminatedEvents(0) {
            chatService.endChat(
                chatId = expired.firstChatId,
                finalStatus = ChatStatus.EXPIRED,
                endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
            )
        }

        val abandoned = createMatchWithFirstChat("first-chat-event-abandoned")
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.ABANDONED,
            expectedReason = ChatEndReason.INACTIVITY_TIMEOUT
        ) {
            chatService.endChat(
                chatId = abandoned.firstChatId,
                finalStatus = ChatStatus.ABANDONED,
                endedReason = ChatEndReason.INACTIVITY_TIMEOUT
            )
        }

        val secondChat = createActiveSecondChat()
        assertFirstChatTerminatedEvents(0) {
            chatService.endChat(
                chatId = secondChat.secondChatId,
                finalStatus = ChatStatus.ABANDONED,
                endedReason = ChatEndReason.INACTIVITY_TIMEOUT
            )
        }
    }

    @Test
    fun `user block and account deletion direct terminal paths publish first chat terminated event`() {
        val blocked = createMatchWithFirstChat("first-chat-event-block")
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.CANCELLED,
            expectedReason = ChatEndReason.USER_BLOCK
        ) {
            userBlockCommandService.blockUserAndContain(
                blockerUserId = blocked.userAId,
                blockedUserId = blocked.userBId,
                source = UserBlockSource.MANUAL
            )
        }

        val deleted = createMatchWithFirstChat("first-chat-event-deleted")
        assertFirstChatTerminatedEvents(
            expectedCount = 1,
            expectedStatus = ChatStatus.CANCELLED,
            expectedReason = ChatEndReason.USER_DELETED
        ) {
            accountDeletionService.closeActiveEngagementsForDeletedUser(
                userId = deleted.userAId,
                now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
            )
        }
    }

    private fun assertFirstChatTerminatedEvents(
        expectedCount: Int,
        expectedStatus: ChatStatus? = null,
        expectedReason: ChatEndReason? = null,
        action: () -> Unit
    ) {
        val before = firstChatTerminatedEvents().size

        action()

        val events = firstChatTerminatedEvents().drop(before)
        assertEquals(expectedCount, events.size)
        if (expectedCount == 1) {
            expectedStatus?.let { assertEquals(it, events.single().finalStatus) }
            expectedReason?.let { assertEquals(it, events.single().endedReason) }
        }
    }

    private fun firstChatTerminatedEvents(): List<FirstChatTerminatedEvent> =
        applicationEvents.stream(FirstChatTerminatedEvent::class.java).toList()

    private fun expired(request: com.reals.backend.domain.ChatExitRequest): com.reals.backend.domain.ChatExitRequest {
        request.createdAt = OffsetDateTime.now().minusMinutes(1)
        return chatExitRequestRepository.saveAndFlush(request)
    }
}
