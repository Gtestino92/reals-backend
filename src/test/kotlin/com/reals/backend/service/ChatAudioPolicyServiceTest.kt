package com.reals.backend.service

import com.reals.backend.config.ChatAudioProperties
import com.reals.backend.config.FirstChatAudioProperties
import com.reals.backend.config.SecondChatAudioProperties
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.FirstChatGuidance
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.FirstChatGuidanceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.util.UUID

class ChatAudioPolicyServiceTest {

    @Test
    fun `feature disabled returns disabled policy without hiding limits`() {
        val service = service(enabled = false, requiredAnsweredQuestions = 1)
        val chat = firstChat()

        val policy = service.policyFor(chat, USER_ID, NOW)

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.FEATURE_DISABLED, policy.unavailableReason)
        assertEquals(1, policy.remainingMessages)
    }

    @Test
    fun `first chat answered-question formula unlocks at threshold one`() {
        val service = service(requiredAnsweredQuestions = 1, currentQuestionOrdinal = 2)

        val policy = service.policyFor(firstChat(), USER_ID, NOW)

        assertTrue(policy.enabled)
        assertEquals(1, policy.remainingMessages)
    }

    @Test
    fun `first chat answered-question formula blocks before threshold two`() {
        val service = service(requiredAnsweredQuestions = 2, currentQuestionOrdinal = 2)

        val policy = service.policyFor(firstChat(), USER_ID, NOW)

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.GUIDANCE_REQUIRED, policy.unavailableReason)
    }

    @Test
    fun `first chat threshold two unlocks when question three is active`() {
        val service = service(requiredAnsweredQuestions = 2, currentQuestionOrdinal = 3)

        val policy = service.policyFor(firstChat(), USER_ID, NOW)

        assertTrue(policy.enabled)
    }

    @Test
    fun `first chat guidance absent is explicit unavailable reason`() {
        val service = service(currentQuestionOrdinal = null)

        val policy = service.policyFor(firstChat(), USER_ID, NOW)

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.GUIDANCE_NOT_AVAILABLE, policy.unavailableReason)
    }

    @Test
    fun `first chat remaining count moves from one to zero`() {
        val service = service(currentQuestionOrdinal = 3, usedAudioMessages = 1)

        val policy = service.policyFor(firstChat(), USER_ID, NOW)

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.LIMIT_REACHED, policy.unavailableReason)
        assertEquals(0, policy.remainingMessages)
    }

    @Test
    fun `second chat waits for both participants before conversation starts`() {
        val policy = service().policyFor(secondChat(conversationStartedAt = null), USER_ID, NOW)

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.WAITING_FOR_BOTH, policy.unavailableReason)
        assertNull(policy.remainingMessages)
    }

    @Test
    fun `second chat unlocks at exact configured boundary and remains unlimited`() {
        val conversationStartedAt = NOW.minusMinutes(10)

        val policy = service().policyFor(
            secondChat(conversationStartedAt = conversationStartedAt),
            USER_ID,
            NOW
        )

        assertTrue(policy.enabled)
        assertNull(policy.unavailableReason)
        assertNull(policy.remainingMessages)
    }

    @Test
    fun `second chat reports delay reason immediately before boundary`() {
        val conversationStartedAt = NOW.minusMinutes(10).plusNanos(1)

        val policy = service().policyFor(
            secondChat(conversationStartedAt = conversationStartedAt),
            USER_ID,
            NOW
        )

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.WAITING_DELAY, policy.unavailableReason)
        assertEquals(conversationStartedAt.plusMinutes(10), policy.enabledAt)
    }

    private fun service(
        enabled: Boolean = true,
        requiredAnsweredQuestions: Int = 1,
        currentQuestionOrdinal: Int? = 2,
        usedAudioMessages: Long = 0
    ): ChatAudioPolicyService {
        val guidanceRepository = Mockito.mock(FirstChatGuidanceRepository::class.java)
        val chatMessageRepository = Mockito.mock(ChatMessageRepository::class.java)
        if (currentQuestionOrdinal != null) {
            Mockito.`when`(guidanceRepository.findByChatId(CHAT_ID))
                .thenReturn(
                    FirstChatGuidance(
                        chatId = CHAT_ID,
                        currentQuestionId = "q-$currentQuestionOrdinal",
                        currentQuestionText = "Question $currentQuestionOrdinal",
                        currentQuestionOrdinal = currentQuestionOrdinal,
                        currentQuestionActivatedAt = NOW.minusMinutes(1)
                    )
                )
        }
        Mockito.`when`(
            chatMessageRepository.countByChatSessionIdAndSenderIdAndMessageType(CHAT_ID, USER_ID, ChatMessageType.AUDIO)
        ).thenReturn(usedAudioMessages)

        return ChatAudioPolicyService(
            audioProperties = ChatAudioProperties(enabled = enabled),
            firstChatAudioProperties = FirstChatAudioProperties(
                maxPerUser = 1,
                requiredAnsweredGuidanceQuestions = requiredAnsweredQuestions
            ),
            secondChatAudioProperties = SecondChatAudioProperties(enabledAfterConversationMinutes = 10),
            guidanceRepository = guidanceRepository,
            chatMessageRepository = chatMessageRepository,
            firstChatInactivityThresholdMinutes = 5
        )
    }

    private fun firstChat(): Chat =
        Chat(
            id = CHAT_ID,
            matchId = MATCH_ID,
            chatType = ChatType.FIRST_CHAT,
            status = ChatStatus.ACTIVE,
            startedAt = NOW.minusMinutes(1),
            timeoutAt = NOW.plusMinutes(14)
        )

    private fun secondChat(conversationStartedAt: OffsetDateTime?): Chat =
        Chat(
            id = CHAT_ID,
            matchId = MATCH_ID,
            connectionId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            chatType = ChatType.SECOND_CHAT,
            status = ChatStatus.ACTIVE,
            startedAt = NOW.minusMinutes(30),
            conversationStartedAt = conversationStartedAt,
            timeoutAt = NOW.plusHours(1)
        )

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-01-01T12:00:00Z")
        val CHAT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val MATCH_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")
    }
}
