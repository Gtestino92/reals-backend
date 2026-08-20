package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.MatchState
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime

@TestPropertySource(
    properties = [
        "chat.first-chat.approval.min-elapsed-minutes=5",
        "chat.first-chat.approval.min-messages-per-user=2",
        "chat.first-chat.min-messages-per-user=0"
    ]
)
class ChatDecisionMinMessagesIntegrationTest : BaseIT() {

    @Test
    fun `approving first chat before minimum elapsed returns stable code`() {
        val setup = createMatchWithFirstChat()
        sendBilateralMessages(setup.firstChatId, setup.userAId, setup.userBId, countPerUser = 2)

        val exception = assertThrows<DomainConflictException> {
            chatService.recordChatDecision(
                matchId = setup.matchId,
                userId = setup.userAId,
                decision = ChatContinueDecision.APPROVED
            )
        }

        assertEquals(DomainErrorCode.FIRST_CHAT_APPROVAL_TOO_EARLY, exception.code)
    }

    @Test
    fun `approving first chat with insufficient approving user messages returns stable code`() {
        val setup = createMatchWithFirstChat()
        chatService.sendMessage(setup.firstChatId, setup.userAId, "A message")
        repeat(2) { index -> chatService.sendMessage(setup.firstChatId, setup.userBId, "B message $index") }
        moveFirstChatStartIntoPast(setup.firstChatId, minutes = 5)

        val exception = assertThrows<DomainConflictException> {
            chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        }

        assertEquals(DomainErrorCode.FIRST_CHAT_APPROVAL_PARTICIPATION_REQUIRED, exception.code)
    }

    @Test
    fun `approving first chat with insufficient partner messages returns stable code`() {
        val setup = createMatchWithFirstChat()
        repeat(2) { index -> chatService.sendMessage(setup.firstChatId, setup.userAId, "A message $index") }
        chatService.sendMessage(setup.firstChatId, setup.userBId, "B message")
        moveFirstChatStartIntoPast(setup.firstChatId, minutes = 5)

        val exception = assertThrows<DomainConflictException> {
            chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        }

        assertEquals(DomainErrorCode.FIRST_CHAT_APPROVAL_PARTICIPATION_REQUIRED, exception.code)
    }

    @Test
    fun `approving first chat at exact elapsed and message boundary succeeds`() {
        val setup = createMatchWithFirstChat()
        sendBilateralMessages(setup.firstChatId, setup.userAId, setup.userBId, countPerUser = 2)
        moveFirstChatStartIntoPast(setup.firstChatId, minutes = 5)

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

        assertEquals(
            ChatContinueDecision.APPROVED,
            chatDecisionRepository.findByChatId(setup.firstChatId)?.userADecision
        )
    }

    @Test
    fun `rejected first chat remains available without approval prerequisites`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.REJECTED)

        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(setup.firstChatId).orElseThrow().status)
    }

    @Test
    fun `reliability disabled does not disable approval eligibility`() {
        val setup = createMatchWithFirstChat()

        val exception = assertThrows<DomainConflictException> {
            chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        }

        assertEquals(DomainErrorCode.FIRST_CHAT_APPROVAL_TOO_EARLY, exception.code)
    }

    @Test
    fun `repeated approved decision remains already submitted after eligible approval`() {
        val setup = createMatchWithFirstChat()
        sendBilateralMessages(setup.firstChatId, setup.userAId, setup.userBId, countPerUser = 2)
        moveFirstChatStartIntoPast(setup.firstChatId, minutes = 5)
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

        val exception = assertThrows<DomainConflictException> {
            chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        }

        assertEquals(DomainErrorCode.CHAT_DECISION_ALREADY_SUBMITTED, exception.code)
    }

    @Test
    fun `mutual eligible approvals transition to visual review`() {
        val setup = createMatchWithFirstChat()
        sendBilateralMessages(setup.firstChatId, setup.userAId, setup.userBId, countPerUser = 2)
        moveFirstChatStartIntoPast(setup.firstChatId, minutes = 5)

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)
        assertNotNull(visualReviewRepository.findByMatchId(setup.matchId))
    }

    private fun sendBilateralMessages(
        chatId: java.util.UUID,
        userAId: java.util.UUID,
        userBId: java.util.UUID,
        countPerUser: Int
    ) {
        repeat(countPerUser) { index ->
            chatService.sendMessage(chatId, userAId, "A message $index")
            chatService.sendMessage(chatId, userBId, "B message $index")
        }
    }

    private fun moveFirstChatStartIntoPast(
        chatId: java.util.UUID,
        minutes: Long
    ) {
        val chat = chatRepository.findById(chatId).orElseThrow()
        chat.startedAt = OffsetDateTime.now().minusMinutes(minutes)
        chatRepository.saveAndFlush(chat)
    }
}
