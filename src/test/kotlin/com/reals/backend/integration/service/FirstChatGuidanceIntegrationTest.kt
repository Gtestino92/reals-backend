package com.reals.backend.integration.service

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ConversationPromptSnapshotSourceType
import com.reals.backend.domain.FirstChatGuidance
import com.reals.backend.domain.Gender
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.affinity.AffinityDerivedSnapshotInitializationService
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException
import java.time.OffsetDateTime
import java.util.UUID

class FirstChatGuidanceIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var affinityDerivedSnapshotInitializationService: AffinityDerivedSnapshotInitializationService

    @Test
    fun `first chat initializes guidance and second chat does not`() {
        val firstChatSetup = createMatchWithFirstChat("guidance-init")

        val guidance = firstChatGuidanceRepository.findByChatId(firstChatSetup.firstChatId)
        val snapshots = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(firstChatSetup.firstChatId)

        assertNotNull(guidance)
        assertEquals(3, snapshots.size)
        assertEquals(1, guidance?.currentQuestionOrdinal)
        assertEquals(snapshots.first().sourceQuestionId, guidance?.currentQuestionId)
        assertEquals(snapshots.first().promptText, guidance?.currentQuestionText)
        assertNull(guidance?.userANextRequestedAt)
        assertNull(guidance?.userBNextRequestedAt)
        assertNull(guidance?.completedAt)

        val secondChatSetup = createActiveSecondChat()

        assertNull(firstChatGuidanceRepository.findByChatId(secondChatSetup.secondChatId))
    }

    @Test
    fun `no-answer first chat gets generic prompt snapshots and zero visual indicators`() {
        val setup = createMatchWithFirstChat("guidance-no-answer")
        val snapshots = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(setup.firstChatId)

        assertEquals(3, snapshots.size)
        assertTrue(snapshots.all { it.sourceType == ConversationPromptSnapshotSourceType.GENERIC })
        assertTrue(snapshots.all { it.sourceQuestionSemanticVersion == null })
        assertTrue(snapshots.all { it.categoryId == null })
        assertTrue(snapshots.all { it.conversationKind == null })
        assertTrue(visualReviewAffinityIndicatorRepository.findByMatchIdOrderByOrdinal(setup.matchId).isEmpty())
    }

    @Test
    fun `shared affinity snapshots are immutable after answer changes`() {
        val setup = createAnsweredMatchWithFirstChat(
            emailPrefix = "guidance-shared-immutable",
            questionId = "CINEMA_IMPORTANCE_001",
            userAAnswer = "VERY_IMPORTANT",
            userBAnswer = "IMPORTANT"
        )
        val originalPrompts = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(setup.firstChatId)
        val originalIndicators = visualReviewAffinityIndicatorRepository.findByMatchIdOrderByOrdinal(setup.matchId)

        answerAffinityQuestion(setup.userAId, "CINEMA_IMPORTANCE_001", "NOT_FOR_ME")
        baseAffinityQuestionAnswerRepository.deleteByProfileIdAndQuestionId(
            profileId = profileRepository.findByUserId(setup.userBId)!!.id,
            questionId = "CINEMA_IMPORTANCE_001"
        )

        assertEquals(originalPrompts, conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(setup.firstChatId))
        assertEquals(originalIndicators, visualReviewAffinityIndicatorRepository.findByMatchIdOrderByOrdinal(setup.matchId))
        assertTrue(originalPrompts.any { it.sourceType == ConversationPromptSnapshotSourceType.AFFINITY })
        assertTrue(originalPrompts.none { it.promptText.contains("VERY_IMPORTANT") || it.promptText.contains("IMPORTANT") })
        assertEquals(listOf("CINEMA_SERIES_AND_STORIES"), originalIndicators.map { it.categoryId })
    }

    @Test
    fun `snapshot initialization is replay safe`() {
        val setup = createAnsweredMatchWithFirstChat(
            emailPrefix = "guidance-replay",
            questionId = "CINEMA_IMPORTANCE_001",
            userAAnswer = "VERY_IMPORTANT",
            userBAnswer = "IMPORTANT"
        )
        val chat = chatService.findByIdOrThrow(setup.firstChatId)
        val match = matchService.findByIdOrThrow(setup.matchId)
        val originalPrompts = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(setup.firstChatId)
        val originalIndicators = visualReviewAffinityIndicatorRepository.findByMatchIdOrderByOrdinal(setup.matchId)

        val replayed =
            affinityDerivedSnapshotInitializationService.initializeForFirstChat(
                chat = chat,
                match = match
            )

        assertEquals(originalPrompts, replayed.prompts)
        assertEquals(originalIndicators, replayed.indicators)
        assertEquals(3, conversationPromptSnapshotRepository.countByChatId(setup.firstChatId))
        assertEquals(1, visualReviewAffinityIndicatorRepository.countByMatchId(setup.matchId))
    }

    @Test
    fun `contrast-only pair can get prompt but zero visual indicators`() {
        val setup = createAnsweredMatchWithFirstChat(
            emailPrefix = "guidance-contrast",
            questionId = "MUSIC_MOOD_001",
            userAAnswer = "ENERGETIC",
            userBAnswer = "CHILL"
        )
        val snapshots = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(setup.firstChatId)

        assertEquals("MUSIC_MOOD_001", snapshots.first().sourceQuestionId)
        assertEquals(ConversationPromptSnapshotSourceType.AFFINITY, snapshots.first().sourceType)
        assertTrue(visualReviewAffinityIndicatorRepository.findByMatchIdOrderByOrdinal(setup.matchId).isEmpty())
    }

    @Test
    fun `semantic-version mismatch is neutral for visible snapshots`() {
        val userA = createMatchedUser("guidance-semantic-a", Gender.FEMALE, setOf(Gender.MALE))
        val userB = createMatchedUser("guidance-semantic-b", Gender.MALE, setOf(Gender.FEMALE))
        answerAffinityQuestion(userA, "CINEMA_IMPORTANCE_001", "VERY_IMPORTANT")
        answerAffinityQuestion(userB, "CINEMA_IMPORTANCE_001", "VERY_IMPORTANT")
        val profileA = profileRepository.findByUserId(userA)!!
        val answerA = baseAffinityQuestionAnswerRepository.findByProfileIdAndQuestionId(profileA.id, "CINEMA_IMPORTANCE_001")!!
        answerA.questionSemanticVersion = 0
        baseAffinityQuestionAnswerRepository.saveAndFlush(answerA)

        val match = matchService.createMatch(userA, userB)
        val chat = chatService.startFirstChat(match.id)

        val snapshots = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(chat.id)
        assertTrue(snapshots.all { it.sourceType == ConversationPromptSnapshotSourceType.GENERIC })
        assertTrue(visualReviewAffinityIndicatorRepository.findByMatchIdOrderByOrdinal(match.id).isEmpty())
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
    fun `new guidance advances through persisted snapshots`() {
        val setup = createAnsweredMatchWithFirstChat(
            emailPrefix = "guidance-persisted-advance",
            questionId = "CINEMA_IMPORTANCE_001",
            userAAnswer = "VERY_IMPORTANT",
            userBAnswer = "VERY_IMPORTANT"
        )
        val snapshots = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(setup.firstChatId)

        chatService.sendMessage(setup.firstChatId, setup.userAId, "a".repeat(40))
        chatService.sendMessage(setup.firstChatId, setup.userBId, "b".repeat(40))
        chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
        val advanced = chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userBId)

        assertEquals(2, advanced.questionOrdinal)
        assertEquals(snapshots[1].sourceQuestionId, advanced.questionId)
        assertEquals(snapshots[1].promptText, advanced.questionText)
    }

    @Test
    fun `legacy guidance without snapshots advances generically`() {
        val setup = createMatchWithFirstChat("guidance-legacy")
        val existing = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(setup.firstChatId)
        conversationPromptSnapshotRepository.deleteAll(existing)
        conversationPromptSnapshotRepository.flush()
        val genericQ2 = firstChatGuidedQuestionCatalog.questionFor(setup.firstChatId, 2)

        chatService.sendMessage(setup.firstChatId, setup.userAId, "a".repeat(40))
        chatService.sendMessage(setup.firstChatId, setup.userBId, "b".repeat(40))
        chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
        val advanced = chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userBId)

        assertEquals(genericQ2.id, advanced.questionId)
        assertEquals(genericQ2.text, advanced.questionText)
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

    private fun createAnsweredMatchWithFirstChat(
        emailPrefix: String,
        questionId: String,
        userAAnswer: String,
        userBAnswer: String
    ): MatchFixture {
        val userA = createMatchedUser("$emailPrefix-a", Gender.FEMALE, setOf(Gender.MALE))
        val userB = createMatchedUser("$emailPrefix-b", Gender.MALE, setOf(Gender.FEMALE))
        answerAffinityQuestion(userA, questionId, userAAnswer)
        answerAffinityQuestion(userB, questionId, userBAnswer)
        val match = matchService.createMatch(userA, userB)
        val chat = chatService.startFirstChat(match.id)

        return MatchFixture(
            userAId = userA,
            userBId = userB,
            matchId = match.id,
            firstChatId = chat.id
        )
    }

    private fun createMatchedUser(
        emailPrefix: String,
        gender: Gender,
        lookingForGenders: Set<Gender>
    ): UUID =
        createActiveProfile(
            email = "$emailPrefix-${UUID.randomUUID()}@example.com",
            displayName = "Match user",
            gender = gender,
            lookingForGenders = lookingForGenders
        )
}
