package com.reals.backend.service

import com.reals.backend.config.ChatAudioProperties
import com.reals.backend.config.FirstChatAudioProperties
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.FirstChatGuidance
import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.domain.SecondChatParticipation
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.FirstChatGuidanceRepository
import com.reals.backend.repository.SecondChatParticipationRepository
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
    fun `second chat enables audio immediately after current user joins on time`() {
        val policy = service(secondChatAttendanceStatus = SecondChatAttendanceStatus.ON_TIME)
            .policyFor(secondChat(conversationStartedAt = null), USER_ID, NOW)

        assertTrue(policy.enabled)
        assertNull(policy.unavailableReason)
        assertNull(policy.enabledAt)
        assertNull(policy.remainingMessages)
    }

    @Test
    fun `second chat enables audio immediately after current user joins late`() {
        val policy = service(secondChatAttendanceStatus = SecondChatAttendanceStatus.LATE)
            .policyFor(secondChat(conversationStartedAt = null), USER_ID, NOW)

        assertTrue(policy.enabled)
        assertNull(policy.unavailableReason)
        assertNull(policy.enabledAt)
        assertNull(policy.remainingMessages)
    }

    @Test
    fun `second chat disables audio when current user has not joined`() {
        val policy = service(secondChatAttendanceStatus = SecondChatAttendanceStatus.PENDING)
            .policyFor(secondChat(conversationStartedAt = NOW.minusMinutes(30)), USER_ID, NOW)

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.CHAT_NOT_WRITABLE, policy.unavailableReason)
        assertNull(policy.enabledAt)
        assertNull(policy.remainingMessages)
    }

    @Test
    fun `second chat disables audio when current user participation is missing`() {
        val policy = service(secondChatAttendanceStatus = null)
            .policyFor(secondChat(conversationStartedAt = NOW.minusMinutes(30)), USER_ID, NOW)

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.CHAT_NOT_WRITABLE, policy.unavailableReason)
        assertNull(policy.enabledAt)
        assertNull(policy.remainingMessages)
    }

    @Test
    fun `second chat disables audio when connection id is missing`() {
        val policy = service(secondChatAttendanceStatus = SecondChatAttendanceStatus.ON_TIME)
            .policyFor(
                secondChat(
                    conversationStartedAt = null,
                    connectionId = null
                ),
                USER_ID,
                NOW
            )

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.CHAT_NOT_WRITABLE, policy.unavailableReason)
        assertNull(policy.enabledAt)
        assertNull(policy.remainingMessages)
    }

    @Test
    fun `second chat disables audio for joined user when chat is terminal`() {
        val policy = service(secondChatAttendanceStatus = SecondChatAttendanceStatus.ON_TIME)
            .policyFor(
                secondChat(
                    conversationStartedAt = null,
                    status = ChatStatus.FINISHED
                ),
                USER_ID,
                NOW
            )

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.CHAT_NOT_WRITABLE, policy.unavailableReason)
    }

    @Test
    fun `second chat disables audio for joined user at exact timeout boundary`() {
        val policy = service(secondChatAttendanceStatus = SecondChatAttendanceStatus.ON_TIME)
            .policyFor(
                secondChat(
                    conversationStartedAt = null,
                    timeoutAt = NOW
                ),
                USER_ID,
                NOW
            )

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.CHAT_NOT_WRITABLE, policy.unavailableReason)
    }

    @Test
    fun `second chat global feature flag wins for joined user`() {
        val policy = service(
            enabled = false,
            secondChatAttendanceStatus = SecondChatAttendanceStatus.ON_TIME
        ).policyFor(secondChat(conversationStartedAt = null), USER_ID, NOW)

        assertFalse(policy.enabled)
        assertEquals(ChatAudioUnavailableReason.FEATURE_DISABLED, policy.unavailableReason)
        assertNull(policy.enabledAt)
        assertNull(policy.remainingMessages)
    }

    private fun service(
        enabled: Boolean = true,
        requiredAnsweredQuestions: Int = 1,
        currentQuestionOrdinal: Int? = 2,
        usedAudioMessages: Long = 0,
        secondChatAttendanceStatus: SecondChatAttendanceStatus? = SecondChatAttendanceStatus.ON_TIME
    ): ChatAudioPolicyService {
        val guidanceRepository = Mockito.mock(FirstChatGuidanceRepository::class.java)
        val chatMessageRepository = Mockito.mock(ChatMessageRepository::class.java)
        val secondChatParticipationRepository = Mockito.mock(SecondChatParticipationRepository::class.java)
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
        Mockito.`when`(
            secondChatParticipationRepository.findByConnectionIdAndUserId(CONNECTION_ID, USER_ID)
        ).thenReturn(
            secondChatAttendanceStatus?.let {
                SecondChatParticipation(
                    connectionId = CONNECTION_ID,
                    userId = USER_ID,
                    attendanceStatus = it,
                    joinedAt = if (it == SecondChatAttendanceStatus.ON_TIME || it == SecondChatAttendanceStatus.LATE) {
                        NOW.minusMinutes(1)
                    } else {
                        null
                    }
                )
            }
        )

        return ChatAudioPolicyService(
            audioProperties = ChatAudioProperties(enabled = enabled),
            firstChatAudioProperties = FirstChatAudioProperties(
                maxPerUser = 1,
                requiredAnsweredGuidanceQuestions = requiredAnsweredQuestions
            ),
            guidanceRepository = guidanceRepository,
            chatMessageRepository = chatMessageRepository,
            secondChatParticipationRepository = secondChatParticipationRepository,
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

    private fun secondChat(
        conversationStartedAt: OffsetDateTime?,
        connectionId: UUID? = CONNECTION_ID,
        status: ChatStatus = ChatStatus.ACTIVE,
        timeoutAt: OffsetDateTime = NOW.plusHours(1)
    ): Chat =
        Chat(
            id = CHAT_ID,
            matchId = MATCH_ID,
            connectionId = connectionId,
            chatType = ChatType.SECOND_CHAT,
            status = status,
            startedAt = NOW.minusMinutes(30),
            conversationStartedAt = conversationStartedAt,
            timeoutAt = timeoutAt
        )

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-01-01T12:00:00Z")
        val CHAT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CONNECTION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val MATCH_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")
    }
}
