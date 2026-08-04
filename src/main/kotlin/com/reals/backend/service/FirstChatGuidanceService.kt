package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ConversationPromptSnapshot
import com.reals.backend.domain.FirstChatGuidance
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ConversationPromptSnapshotRepository
import com.reals.backend.repository.FirstChatGuidanceRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

data class FirstChatGuidedQuestion(
    val id: String,
    val text: String
)

data class FirstChatGuidanceState(
    val questionId: String,
    val questionText: String,
    val questionOrdinal: Int,
    val maxQuestions: Int,
    val requiredCharacters: Int,
    val canRequestNext: Boolean,
    val myNextRequested: Boolean,
    val completed: Boolean
)

@Component
class FirstChatGuidedQuestionCatalog(
    private val objectMapper: ObjectMapper,
    private val resourceLoader: ResourceLoader,
    @param:Value("\${chat.first-chat.guidance.catalog:classpath:first-chat-guided-questions.es.json}")
    private val catalogLocation: String,
    @param:Value("\${chat.first-chat.guidance.max-questions:3}")
    private val maxQuestions: Int
) {

    private val questions: List<FirstChatGuidedQuestion> = loadQuestions()

    fun questionFor(
        chatId: UUID,
        ordinal: Int
    ): FirstChatGuidedQuestion {
        require(ordinal in 1..questions.size) {
            "Question ordinal $ordinal is outside catalog size ${questions.size}"
        }
        return questions[questionIndex(chatId, ordinal)]
    }

    fun sequenceFor(
        chatId: UUID,
        count: Int
    ): List<FirstChatGuidedQuestion> =
        (1..count).map { questionFor(chatId, it) }

    private fun loadQuestions(): List<FirstChatGuidedQuestion> {
        require(maxQuestions > 0) {
            "chat.first-chat.guidance.max-questions must be positive"
        }

        val resource = resourceLoader.getResource(catalogLocation)
        val loaded =
            resource.inputStream.use { input ->
                objectMapper.readValue(input, Array<FirstChatGuidedQuestion>::class.java).toList()
            }

        require(loaded.isNotEmpty()) {
            "First-chat guided question catalog must not be empty"
        }
        require(loaded.size >= maxQuestions) {
            "First-chat guided question catalog must contain at least $maxQuestions questions"
        }

        val duplicateIds =
            loaded.groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys

        require(duplicateIds.isEmpty()) {
            "First-chat guided question catalog contains duplicate ids: ${duplicateIds.joinToString()}"
        }

        loaded.forEach { question ->
            require(question.id.length <= 64)
            require(question.id.isNotBlank()) {
                "First-chat guided question catalog contains a blank id"
            }
            require(question.text.isNotBlank()) {
                "First-chat guided question catalog contains blank text for id ${question.id}"
            }
        }

        return loaded
    }

    private fun questionIndex(
        chatId: UUID,
        ordinal: Int
    ): Int {
        val catalogSize = questions.size
        val seed = deterministicSeed(chatId)
        val start = seed.mod(BigInteger.valueOf(catalogSize.toLong())).toInt()
        val step = coprimeStep(
            seed = seed.shiftRight(16),
            modulo = catalogSize
        )

        return (start + (ordinal - 1) * step).mod(catalogSize)
    }

    private fun deterministicSeed(chatId: UUID): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(chatId.toString().toByteArray(StandardCharsets.UTF_8))
        return BigInteger(1, digest)
    }

    private fun coprimeStep(
        seed: BigInteger,
        modulo: Int
    ): Int {
        var step =
            seed.mod(BigInteger.valueOf((modulo - 1).toLong()))
                .toInt() + 1

        while (gcd(step, modulo) != 1) {
            step = (step % modulo) + 1
        }

        return step
    }

    private fun gcd(
        left: Int,
        right: Int
    ): Int {
        var a = left
        var b = right
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }
}

@Service
@Transactional
class FirstChatGuidanceService(
    private val guidanceRepository: FirstChatGuidanceRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val matchService: MatchService,
    private val questionCatalog: FirstChatGuidedQuestionCatalog,
    private val promptSnapshotRepository: ConversationPromptSnapshotRepository,
    @param:Value("\${chat.first-chat.guidance.required-characters:40}")
    private val requiredCharacters: Int,
    @param:Value("\${chat.first-chat.guidance.max-questions:3}")
    private val maxQuestions: Int
) {

    init {
        require(requiredCharacters >= 0) {
            "chat.first-chat.guidance.required-characters must not be negative"
        }
        require(maxQuestions > 0) {
            "chat.first-chat.guidance.max-questions must be positive"
        }
    }

    fun initializeForFirstChat(
        chat: Chat,
        now: OffsetDateTime = OffsetDateTime.now()
    ): FirstChatGuidance {
        val question = snapshotOrGenericQuestion(chat.id, 1)

        return guidanceRepository.save(
            FirstChatGuidance(
                chatId = chat.id,
                currentQuestionId = question.questionId,
                currentQuestionText = question.questionText,
                currentQuestionOrdinal = 1,
                currentQuestionActivatedAt = now,
                completedAt = if (maxQuestions == 1) now else null,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    fun findStateForUser(
        chat: Chat,
        userId: UUID
    ): FirstChatGuidanceState? {
        val guidance = guidanceRepository.findByChatId(chat.id)
            ?: return null

        normalizeFinalQuestionCompletionIfNeeded(guidance)

        return guidance.toState(chat, userId)
    }

    fun requestNext(
        chat: Chat,
        userId: UUID
    ): FirstChatGuidanceState {
        val guidance =
            guidanceRepository.findByChatIdForUpdate(chat.id)
                ?: throw guidanceNotFound()

        normalizeFinalQuestionCompletionIfNeeded(guidance)

        if (guidance.completedAt != null) {
            throw DomainConflictException(
                code = DomainErrorCode.FIRST_CHAT_GUIDANCE_COMPLETED,
                message = "First-chat guidance is completed"
            )
        }

        val match = matchService.findByIdOrThrow(chat.matchId)
        val alreadyRequested =
            when (userId) {
                match.userAId -> guidance.userANextRequestedAt != null
                match.userBId -> guidance.userBNextRequestedAt != null
                else -> throw IllegalArgumentException("User $userId does not belong to chat ${chat.id}")
            }

        if (alreadyRequested) {
            throw DomainConflictException(
                code = DomainErrorCode.FIRST_CHAT_GUIDANCE_NEXT_ALREADY_REQUESTED,
                message = "Next question has already been requested"
            )
        }

        val participationCharacters =
            participationCharacters(
                chatId = chat.id,
                userId = userId,
                since = guidance.currentQuestionActivatedAt
            )

        if (participationCharacters < requiredCharacters) {
            throw DomainConflictException(
                code = DomainErrorCode.FIRST_CHAT_GUIDANCE_PARTICIPATION_REQUIRED,
                message = "Minimum participation is required before requesting another question"
            )
        }

        val now = OffsetDateTime.now()
        when (userId) {
            match.userAId -> guidance.userANextRequestedAt = now
            match.userBId -> guidance.userBNextRequestedAt = now
        }

        if (guidance.userANextRequestedAt != null && guidance.userBNextRequestedAt != null) {
            advanceOrComplete(chat = chat, guidance = guidance, now = now)
        }

        guidance.updatedAt = now

        return guidanceRepository.save(guidance)
            .toState(chat, userId)
    }

    private fun advanceOrComplete(
        chat: Chat,
        guidance: FirstChatGuidance,
        now: OffsetDateTime
    ) {
        val nextOrdinal = guidance.currentQuestionOrdinal + 1
        val nextQuestion = snapshotOrGenericQuestion(chat.id, nextOrdinal)

        guidance.currentQuestionId = nextQuestion.questionId
        guidance.currentQuestionText = nextQuestion.questionText
        guidance.currentQuestionOrdinal = nextOrdinal
        guidance.currentQuestionActivatedAt = now
        guidance.userANextRequestedAt = null
        guidance.userBNextRequestedAt = null
        guidance.completedAt = if (nextOrdinal >= maxQuestions) now else null
    }

    private fun normalizeFinalQuestionCompletionIfNeeded(guidance: FirstChatGuidance) {
        if (guidance.currentQuestionOrdinal < maxQuestions || guidance.completedAt != null) {
            return
        }

        val now = OffsetDateTime.now()
        guidance.completedAt = now
        guidance.userANextRequestedAt = null
        guidance.userBNextRequestedAt = null
        guidance.updatedAt = now
        guidanceRepository.save(guidance)
    }

    private fun snapshotOrGenericQuestion(
        chatId: UUID,
        ordinal: Int
    ): GuidanceQuestion =
        promptSnapshotRepository.findByChatIdAndOrdinal(
            chatId = chatId,
            ordinal = ordinal
        )?.toGuidanceQuestion()
            ?: questionCatalog.questionFor(chatId, ordinal)
                .let { GuidanceQuestion(questionId = it.id, questionText = it.text) }

    private fun ConversationPromptSnapshot.toGuidanceQuestion(): GuidanceQuestion =
        GuidanceQuestion(
            questionId = sourceQuestionId,
            questionText = promptText
        )

    private fun FirstChatGuidance.toState(
        chat: Chat,
        userId: UUID
    ): FirstChatGuidanceState {
        val match = matchService.findByIdOrThrow(chat.matchId)
        val myNextRequested =
            when (userId) {
                match.userAId -> userANextRequestedAt != null
                match.userBId -> userBNextRequestedAt != null
                else -> throw IllegalArgumentException("User $userId does not belong to chat ${chat.id}")
            }

        val completed = completedAt != null || currentQuestionOrdinal >= maxQuestions
        val canRequestNext =
            !completed &&
                !myNextRequested &&
                participationCharacters(
                    chatId = chat.id,
                    userId = userId,
                    since = currentQuestionActivatedAt
                ) >= requiredCharacters

        return FirstChatGuidanceState(
            questionId = currentQuestionId,
            questionText = currentQuestionText,
            questionOrdinal = currentQuestionOrdinal,
            maxQuestions = maxQuestions,
            requiredCharacters = requiredCharacters,
            canRequestNext = canRequestNext,
            myNextRequested = myNextRequested,
            completed = completed
        )
    }

    private fun participationCharacters(
        chatId: UUID,
        userId: UUID,
        since: OffsetDateTime
    ): Long =
        chatMessageRepository.sumContentLengthByChatSenderSince(
            chatId = chatId,
            senderId = userId,
            sentAt = since
        )

    private fun guidanceNotFound(): DomainNotFoundException =
        DomainNotFoundException(
            code = DomainErrorCode.FIRST_CHAT_GUIDANCE_NOT_FOUND,
            message = "First-chat guidance was not found"
        )

    private data class GuidanceQuestion(
        val questionId: String,
        val questionText: String
    )
}
