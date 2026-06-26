package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.context.TestPropertySource

@TestPropertySource(
    properties = [
        "chat.first-chat.min-messages-per-user=1"
    ]
)
class ChatDecisionMinMessagesIntegrationTest : BaseIT() {

    @Test
    fun `approving first chat before minimum message count returns stable code`() {
        val setup = createMatchWithFirstChat()

        val exception = assertThrows<DomainConflictException> {
            chatService.recordChatDecision(
                matchId = setup.matchId,
                userId = setup.userAId,
                decision = ChatContinueDecision.APPROVED
            )
        }

        assertEquals(DomainErrorCode.CHAT_MIN_MESSAGES_REQUIRED, exception.code)
    }
}
