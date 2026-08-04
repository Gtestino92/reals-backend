package com.reals.backend.service.affinity

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConversationPromptSnapshot
import com.reals.backend.domain.Match
import com.reals.backend.domain.VisualReviewAffinityIndicator
import com.reals.backend.repository.AffinityQuestionAnswerRepository
import com.reals.backend.repository.ConversationPromptSnapshotRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.VisualReviewAffinityIndicatorRepository
import com.reals.backend.service.FirstChatGuidedQuestionCatalog
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

data class AffinityDerivedSnapshotInitializationResult(
    val prompts: List<ConversationPromptSnapshot>,
    val indicators: List<VisualReviewAffinityIndicator>
)

@Service
@Transactional
class AffinityDerivedSnapshotInitializationService(
    private val promptSnapshotRepository: ConversationPromptSnapshotRepository,
    private val indicatorRepository: VisualReviewAffinityIndicatorRepository,
    private val answerRepository: AffinityQuestionAnswerRepository,
    private val profileRepository: ProfileRepository,
    private val catalogProvider: AffinityQuestionCatalogProvider,
    private val pairEvaluator: AffinityQuestionPairEvaluator,
    genericQuestionCatalog: FirstChatGuidedQuestionCatalog,
    @param:Value("\${chat.first-chat.guidance.max-questions:3}")
    private val maxQuestions: Int
) {
    private val promptSelector = ConversationPromptSnapshotSelector(genericQuestionCatalog)
    private val indicatorSelector = VisualAffinityIndicatorSelector()

    init {
        require(maxQuestions > 0) {
            "chat.first-chat.guidance.max-questions must be positive"
        }
    }

    fun initializeForFirstChat(
        chat: Chat,
        match: Match,
        now: OffsetDateTime = OffsetDateTime.now()
    ): AffinityDerivedSnapshotInitializationResult {
        require(chat.chatType == ChatType.FIRST_CHAT) {
            "Conversation prompt snapshots can only be initialized for first chats"
        }

        val existingPrompts = promptSnapshotRepository.findByChatIdOrderByOrdinal(chat.id)
        if (existingPrompts.size == maxQuestions) {
            return AffinityDerivedSnapshotInitializationResult(
                prompts = existingPrompts,
                indicators = indicatorRepository.findByMatchIdOrderByOrdinal(match.id)
            )
        }

        val catalog = catalogProvider.getCatalog()
        val profiles =
            profileRepository.findByUserIdIn(listOf(match.userAId, match.userBId))
                .associateBy { it.userId }
        val profileA = requireNotNull(profiles[match.userAId]) {
            "Profile not found for match user A"
        }
        val profileB = requireNotNull(profiles[match.userBId]) {
            "Profile not found for match user B"
        }

        val answersByProfile =
            answerRepository.findByProfileIdIn(listOf(profileA.id, profileB.id))
                .groupBy { it.profileId }

        val evidence =
            pairEvaluator.evaluate(
                leftAnswers = answersByProfile[profileA.id].orEmpty().map {
                    AffinityAnswerSnapshot(
                        questionId = it.questionId,
                        questionSemanticVersion = it.questionSemanticVersion,
                        answerCode = it.answerCode
                    )
                },
                rightAnswers = answersByProfile[profileB.id].orEmpty().map {
                    AffinityAnswerSnapshot(
                        questionId = it.questionId,
                        questionSemanticVersion = it.questionSemanticVersion,
                        answerCode = it.answerCode
                    )
                },
                catalog = catalog
            )

        val prompts =
            promptSelector.select(
                chatId = chat.id,
                maxQuestions = maxQuestions,
                catalog = catalog,
                evidence = evidence
            ).map { selection ->
                ConversationPromptSnapshot(
                    chatId = chat.id,
                    ordinal = selection.ordinal,
                    sourceType = selection.sourceType,
                    sourceQuestionId = selection.sourceQuestionId,
                    sourceQuestionSemanticVersion = selection.sourceQuestionSemanticVersion,
                    promptText = selection.promptText,
                    categoryId = selection.categoryId,
                    conversationKind = selection.conversationKind,
                    createdAt = now
                )
            }

        val indicators =
            if (indicatorRepository.countByMatchId(match.id) == 0L) {
                indicatorSelector.select(
                    catalog = catalog,
                    evidence = evidence
                ).map { selection ->
                    VisualReviewAffinityIndicator(
                        matchId = match.id,
                        ordinal = selection.ordinal,
                        categoryId = selection.categoryId,
                        categoryTitle = selection.categoryTitle,
                        createdAt = now
                    )
                }
            } else {
                emptyList()
            }

        return AffinityDerivedSnapshotInitializationResult(
            prompts = promptSnapshotRepository.saveAll(prompts).toList(),
            indicators =
                if (indicators.isEmpty()) {
                    indicatorRepository.findByMatchIdOrderByOrdinal(match.id)
                } else {
                    indicatorRepository.saveAll(indicators).toList()
                }
        )
    }
}
