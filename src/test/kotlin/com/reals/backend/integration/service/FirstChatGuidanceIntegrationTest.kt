package com.reals.backend.integration.service

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.FirstChatGuidance
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException
import java.time.OffsetDateTime
import java.util.UUID

class FirstChatGuidanceIntegrationTest : BaseIT() {

    @Test
    fun `first chat initializes guidance and second chat does not`() {
        val firstChatSetup = createMatchWithFirstChat("guidance-init")

        val guidance = firstChatGuidanceRepository.findByChatId(firstChatSetup.firstChatId)

        assertNotNull(guidance)
        assertEquals(1, guidance?.currentQuestionOrdinal)
        assertNull(guidance?.userANextRequestedAt)
        assertNull(guidance?.userBNextRequestedAt)
        assertNull(guidance?.completedAt)

        val secondChatSetup = createActiveSecondChat()

        assertNull(firstChatGuidanceRepository.findByChatId(secondChatSetup.secondChatId))
    }

    @Test
    fun `deterministic catalog sequence is stable and first three questions are distinct`() {
        val chatId = UUID.randomUUID()

        val first = firstChatGuidedQuestionCatalog.sequenceFor(chatId, 3)
        val second = firstChatGuidedQuestionCatalog.sequenceFor(chatId, 3)

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(3, first.map { it.id }.toSet().size)
    }

    @Test
    fun `guidance requests enforce participation privacy advancement and completion`() {
        val setup = createMatchWithFirstChat("guidance-flow")
        val initialGuidance = firstChatGuidanceRepository.findByChatId(setup.firstChatId)
            ?: error("Expected guidance")
        val q1Id = initialGuidance.currentQuestionId
        val q1Text = initialGuidance.currentQuestionText
        val q1ActivatedAt = initialGuidance.currentQuestionActivatedAt

        val userAInitial =
            chatService.getFirstChatGuidanceState(
                chat = chatService.findByIdOrThrow(setup.firstChatId),
                userId = setup.userAId
            ) ?: error("Expected user A guidance")
        val userBInitial =
            chatService.getFirstChatGuidanceState(
                chat = chatService.findByIdOrThrow(setup.firstChatId),
                userId = setup.userBId
            ) ?: error("Expected user B guidance")

        assertEquals(q1Id, userAInitial.questionId)
        assertEquals(q1Id, userBInitial.questionId)
        assertEquals(q1Text, userAInitial.questionText)
        assertEquals(q1Text, userBInitial.questionText)
        assertFalse(userAInitial.canRequestNext)
        assertFalse(userAInitial.myNextRequested)

        chatService.sendMessage(setup.firstChatId, setup.userBId, "b".repeat(40))

        val belowThreshold =
            assertThrows(DomainConflictException::class.java) {
                chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
            }
        assertEquals(DomainErrorCode.FIRST_CHAT_GUIDANCE_PARTICIPATION_REQUIRED, belowThreshold.code)

        chatService.sendMessage(setup.firstChatId, setup.userAId, "a".repeat(20))
        assertThrows(DomainConflictException::class.java) {
            chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
        }

        chatService.sendMessage(setup.firstChatId, setup.userAId, "c".repeat(20))

        val userARequested =
            chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)

        assertEquals(1, userARequested.questionOrdinal)
        assertEquals(q1Id, userARequested.questionId)
        assertTrue(userARequested.myNextRequested)
        assertFalse(userARequested.canRequestNext)

        val duplicate =
            assertThrows(DomainConflictException::class.java) {
                chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
            }
        assertEquals(DomainErrorCode.FIRST_CHAT_GUIDANCE_NEXT_ALREADY_REQUESTED, duplicate.code)

        val userBBeforeAdvancement =
            chatService.getFirstChatGuidanceState(
                chat = chatService.findByIdOrThrow(setup.firstChatId),
                userId = setup.userBId
            ) ?: error("Expected user B guidance")

        assertFalse(userBBeforeAdvancement.myNextRequested)
        assertTrue(userBBeforeAdvancement.canRequestNext)

        val q2ForUserB =
            chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userBId)

        assertEquals(2, q2ForUserB.questionOrdinal)
        assertNotEquals(q1Id, q2ForUserB.questionId)
        assertFalse(q2ForUserB.myNextRequested)
        assertFalse(q2ForUserB.canRequestNext)

        val q2Guidance = firstChatGuidanceRepository.findByChatId(setup.firstChatId)
            ?: error("Expected Q2 guidance")
        val q2ActivatedAt = q2Guidance.currentQuestionActivatedAt
        val q2Id = q2Guidance.currentQuestionId

        assertNull(q2Guidance.userANextRequestedAt)
        assertNull(q2Guidance.userBNextRequestedAt)
        assertTrue(q2ActivatedAt.isAfter(q1ActivatedAt))

        val userAAfterQ2 =
            chatService.getFirstChatGuidanceState(
                chat = chatService.findByIdOrThrow(setup.firstChatId),
                userId = setup.userAId
            ) ?: error("Expected user A Q2 guidance")
        assertFalse(userAAfterQ2.canRequestNext)

        chatService.sendMessage(setup.firstChatId, setup.userAId, "d".repeat(40))
        chatService.sendMessage(setup.firstChatId, setup.userBId, "e".repeat(15))
        assertThrows(DomainConflictException::class.java) {
            chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userBId)
        }
        chatService.sendMessage(setup.firstChatId, setup.userBId, "f".repeat(25))

        val q2UserARequest =
            chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
        assertEquals(2, q2UserARequest.questionOrdinal)
        assertEquals(q2Id, q2UserARequest.questionId)

        val q3ForUserB =
            chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userBId)

        assertEquals(3, q3ForUserB.questionOrdinal)
        assertNotEquals(q1Id, q3ForUserB.questionId)
        assertNotEquals(q2Id, q3ForUserB.questionId)
        assertTrue(q3ForUserB.completed)
        assertFalse(q3ForUserB.canRequestNext)
        assertFalse(q3ForUserB.myNextRequested)

        val q3Guidance = firstChatGuidanceRepository.findByChatId(setup.firstChatId)
            ?: error("Expected Q3 guidance")
        val q3Id = q3Guidance.currentQuestionId
        val q3Text = q3Guidance.currentQuestionText
        assertEquals(3, q3Guidance.currentQuestionOrdinal)
        assertEquals(q3ForUserB.questionId, q3Id)
        assertNotNull(q3Guidance.completedAt)
        assertNull(q3Guidance.userANextRequestedAt)
        assertNull(q3Guidance.userBNextRequestedAt)

        val completedForUserA =
            chatService.getFirstChatGuidanceState(
                chat = chatService.findByIdOrThrow(setup.firstChatId),
                userId = setup.userAId
            ) ?: error("Expected completed Q3 guidance")

        assertEquals(3, completedForUserA.questionOrdinal)
        assertEquals(q3Id, completedForUserA.questionId)
        assertEquals(q3Text, completedForUserA.questionText)
        assertTrue(completedForUserA.completed)
        assertFalse(completedForUserA.canRequestNext)
        assertFalse(completedForUserA.myNextRequested)

        val completedAgain =
            assertThrows(DomainConflictException::class.java) {
                chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
            }
        assertEquals(DomainErrorCode.FIRST_CHAT_GUIDANCE_COMPLETED, completedAgain.code)
    }

    @Test
    fun `non participant second chat and unavailable first chat cannot mutate guidance`() {
        val setup = createMatchWithFirstChat("guidance-guards")
        val stranger = userService.createUser("guidance-stranger-${UUID.randomUUID()}@example.com")

        assertThrows(AccessDeniedException::class.java) {
            chatService.requestFirstChatGuidanceNext(setup.firstChatId, stranger.id)
        }

        val secondChatSetup = createActiveSecondChat()
        val secondChatFailure =
            assertThrows(DomainConflictException::class.java) {
                chatService.requestFirstChatGuidanceNext(
                    chatId = secondChatSetup.secondChatId,
                    userId = secondChatSetup.userAId
                )
            }
        assertEquals(DomainErrorCode.CHAT_NOT_AVAILABLE, secondChatFailure.code)

        chatService.sendMessage(setup.firstChatId, setup.userAId, "a".repeat(40))
        val chat = chatService.findByIdOrThrow(setup.firstChatId)
        chat.status = ChatStatus.CLOSED
        chatRepository.save(chat)

        val closedFailure =
            assertThrows(DomainConflictException::class.java) {
                chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
            }
        assertEquals(DomainErrorCode.CHAT_NOT_AVAILABLE, closedFailure.code)

        val guidance = firstChatGuidanceRepository.findByChatId(setup.firstChatId)
            ?: error("Expected guidance")
        assertNull(guidance.userANextRequestedAt)
    }

    @Test
    fun `pending mutual cancellation blocks first chat guidance next without mutation`() {
        val setup = createMatchWithFirstChat("guidance-mutual-pending")
        chatService.sendMessage(setup.firstChatId, setup.userAId, "a".repeat(40))
        val before = firstChatGuidanceRepository.findByChatId(setup.firstChatId)
            ?: error("Expected guidance")

        chatExitService.requestMutualCancellation(
            chatId = setup.firstChatId,
            requesterUserId = setup.userAId
        )

        val exception =
            assertThrows(DomainConflictException::class.java) {
                chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
            }
        assertEquals(DomainErrorCode.CHAT_MUTUAL_CANCELLATION_PENDING, exception.code)

        val after = firstChatGuidanceRepository.findByChatId(setup.firstChatId)
            ?: error("Expected guidance")
        assertEquals(before.currentQuestionId, after.currentQuestionId)
        assertEquals(before.currentQuestionOrdinal, after.currentQuestionOrdinal)
        assertEquals(before.currentQuestionActivatedAt, after.currentQuestionActivatedAt)
        assertNull(after.userANextRequestedAt)
        assertNull(after.userBNextRequestedAt)
    }

    @Test
    fun `chat cannot have duplicate guidance rows`() {
        val setup = createMatchWithFirstChat("guidance-unique")
        val existing = firstChatGuidanceRepository.findByChatId(setup.firstChatId)
            ?: error("Expected guidance")

        assertThrows(DataIntegrityViolationException::class.java) {
            firstChatGuidanceRepository.saveAndFlush(
                FirstChatGuidance(
                    chatId = setup.firstChatId,
                    currentQuestionId = existing.currentQuestionId,
                    currentQuestionText = existing.currentQuestionText,
                    currentQuestionOrdinal = existing.currentQuestionOrdinal,
                    currentQuestionActivatedAt = OffsetDateTime.now()
                )
            )
        }
    }
}
