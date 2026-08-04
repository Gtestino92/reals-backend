package com.reals.backend.service.affinity

import com.reals.backend.domain.ConversationPromptSnapshotKind
import com.reals.backend.domain.ConversationPromptSnapshotSourceType
import com.reals.backend.service.FirstChatGuidedQuestionCatalog

data class ConversationPromptSnapshotSelection(
    val ordinal: Int,
    val sourceType: ConversationPromptSnapshotSourceType,
    val sourceQuestionId: String,
    val sourceQuestionSemanticVersion: Int?,
    val promptText: String,
    val categoryId: String?,
    val conversationKind: ConversationPromptSnapshotKind?
)

data class VisualAffinityIndicatorSelection(
    val ordinal: Int,
    val categoryId: String,
    val categoryTitle: String
)

class ConversationPromptSnapshotSelector(
    private val genericQuestionCatalog: FirstChatGuidedQuestionCatalog
) {
    fun select(
        chatId: java.util.UUID,
        maxQuestions: Int,
        catalog: AffinityQuestionCatalog,
        evidence: PairAffinityEvidence
    ): List<ConversationPromptSnapshotSelection> {
        val questionOrder =
            catalog.questions.withIndex()
                .associate { it.value.id to it.index }
        val categoriesById = catalog.categories.associateBy { it.id }
        val activeQuestionsById = catalog.activeQuestions.associateBy { it.id }

        val orderedEligible =
            evidence.questionSignals
                .mapNotNull { signal ->
                    val question = activeQuestionsById[signal.questionId] ?: return@mapNotNull null
                    val category = categoriesById[signal.categoryId] ?: return@mapNotNull null
                    if (
                        signal.conversationPotential <= 0.0 ||
                        signal.conversationKind !in setOf(
                            ConversationKind.SHARED_AFFINITY,
                            ConversationKind.CONSTRUCTIVE_CONTRAST
                        ) ||
                        signal.sensitivity != AffinitySensitivity.STANDARD
                    ) {
                        return@mapNotNull null
                    }
                    EligibleConversationSignal(
                        signal = signal,
                        question = question,
                        categoryDisplayOrder = category.displayOrder,
                        questionOrder = questionOrder[question.id] ?: Int.MAX_VALUE
                    )
                }
                .sortedWith(
                    compareByDescending<EligibleConversationSignal> { it.signal.conversationPotential }
                        .thenBy { it.categoryDisplayOrder }
                        .thenBy { it.questionOrder }
                        .thenBy { it.question.id }
                )

        val selectedAffinity = mutableListOf<EligibleConversationSignal>()
        val selectedCategories = mutableSetOf<String>()
        val selectedQuestionIds = mutableSetOf<String>()

        orderedEligible.forEach { candidate ->
            if (
                selectedAffinity.size < maxQuestions &&
                selectedCategories.add(candidate.signal.categoryId) &&
                selectedQuestionIds.add(candidate.question.id)
            ) {
                selectedAffinity += candidate
            }
        }

        orderedEligible.forEach { candidate ->
            if (selectedAffinity.size >= maxQuestions) {
                return@forEach
            }
            if (selectedQuestionIds.add(candidate.question.id)) {
                selectedAffinity += candidate
            }
        }

        val selections =
            selectedAffinity.map { candidate ->
                ConversationPromptSnapshotSelection(
                    ordinal = 0,
                    sourceType = ConversationPromptSnapshotSourceType.AFFINITY,
                    sourceQuestionId = candidate.question.id,
                    sourceQuestionSemanticVersion = candidate.question.semanticVersion,
                    promptText = candidate.question.prompt,
                    categoryId = candidate.signal.categoryId,
                    conversationKind = candidate.signal.conversationKind.toSnapshotKind()
                )
            }.toMutableList()

        if (selections.size < maxQuestions) {
            genericQuestionCatalog.sequenceFor(chatId, maxQuestions)
                .forEach { question ->
                    if (selections.size >= maxQuestions) {
                        return@forEach
                    }
                    if (selections.none { it.sourceQuestionId == question.id }) {
                        selections += ConversationPromptSnapshotSelection(
                            ordinal = 0,
                            sourceType = ConversationPromptSnapshotSourceType.GENERIC,
                            sourceQuestionId = question.id,
                            sourceQuestionSemanticVersion = null,
                            promptText = question.text,
                            categoryId = null,
                            conversationKind = null
                        )
                    }
                }
        }

        return selections.mapIndexed { index, selection ->
            selection.copy(ordinal = index + 1)
        }
    }

    private fun ConversationKind.toSnapshotKind(): ConversationPromptSnapshotKind =
        when (this) {
            ConversationKind.SHARED_AFFINITY -> ConversationPromptSnapshotKind.SHARED_AFFINITY
            ConversationKind.CONSTRUCTIVE_CONTRAST -> ConversationPromptSnapshotKind.CONSTRUCTIVE_CONTRAST
            ConversationKind.NEUTRAL,
            ConversationKind.NOT_ELIGIBLE -> error("Ineligible conversation kind $this")
        }

    private data class EligibleConversationSignal(
        val signal: PairAffinityQuestionSignal,
        val question: AffinityQuestion,
        val categoryDisplayOrder: Int,
        val questionOrder: Int
    )
}

class VisualAffinityIndicatorSelector {
    fun select(
        catalog: AffinityQuestionCatalog,
        evidence: PairAffinityEvidence,
        limit: Int = 3
    ): List<VisualAffinityIndicatorSelection> {
        val categoriesById = catalog.categories.associateBy { it.id }

        return evidence.questionSignals
            .filter { signal ->
                signal.conversationKind == ConversationKind.SHARED_AFFINITY &&
                    signal.conversationPotential > 0.0 &&
                    signal.sensitivity == AffinitySensitivity.STANDARD
            }
            .groupBy { it.categoryId }
            .mapNotNull { (categoryId, signals) ->
                val category = categoriesById[categoryId] ?: return@mapNotNull null
                EligibleIndicatorCategory(
                    categoryId = categoryId,
                    categoryTitle = category.title,
                    categoryDisplayOrder = category.displayOrder,
                    maxConversationPotential = signals.maxOf { it.conversationPotential }
                )
            }
            .sortedWith(
                compareByDescending<EligibleIndicatorCategory> { it.maxConversationPotential }
                    .thenBy { it.categoryDisplayOrder }
                    .thenBy { it.categoryId }
            )
            .take(limit)
            .mapIndexed { index, category ->
                VisualAffinityIndicatorSelection(
                    ordinal = index + 1,
                    categoryId = category.categoryId,
                    categoryTitle = category.categoryTitle
                )
            }
    }

    private data class EligibleIndicatorCategory(
        val categoryId: String,
        val categoryTitle: String,
        val categoryDisplayOrder: Int,
        val maxConversationPotential: Double
    )
}
