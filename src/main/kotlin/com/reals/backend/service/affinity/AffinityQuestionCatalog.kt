package com.reals.backend.service.affinity

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class AffinityQuestionCatalog(
    val catalogVersion: String,
    val categories: List<AffinityQuestionCategory>,
    val questions: List<AffinityQuestion>
) {
    val activeQuestions: List<AffinityQuestion>
        get() = questions.filter { it.status == AffinityQuestionStatus.ACTIVE }

    fun categoryById(id: String): AffinityQuestionCategory? =
        categories.firstOrNull { it.id == id }

    fun activeQuestionById(id: String): AffinityQuestion? =
        activeQuestions.firstOrNull { it.id == id }
}

data class AffinityQuestionCategory(
    val id: String,
    val title: String,
    val description: String? = null,
    val displayOrder: Int
)

data class AffinityQuestion(
    val id: String,
    val semanticVersion: Int,
    val contentVersion: Int,
    val status: AffinityQuestionStatus,
    val categoryId: String,
    val primaryTopic: String,
    val topicTags: List<String> = emptyList(),
    val construct: AffinityConstruct,
    val answerType: AffinityAnswerType,
    val prompt: String,
    val options: List<AffinityAnswerOption>,
    val rankingPolicy: RankingComparisonPolicyConfig,
    val conversationPolicy: ConversationComparisonPolicyConfig,
    val sensitivity: AffinitySensitivity,
    val rankingEnabled: Boolean,
    val conversationEnabled: Boolean
) {
    fun optionByCode(code: String): AffinityAnswerOption? =
        options.firstOrNull { it.code == code }
}

data class AffinityAnswerOption(
    val code: String,
    val label: String,
    val displayOrder: Int,
    val value: Double? = null
)

enum class AffinityQuestionStatus {
    ACTIVE,
    DEPRECATED
}

enum class AffinityAnswerType {
    SINGLE_CHOICE,
    ORDINAL_SCALE
}

enum class AffinityConstruct {
    DOMAIN_ENGAGEMENT,
    TASTE_PREFERENCE,
    SHARED_ACTIVITY_ORIENTATION,
    VALUES_ALIGNMENT,
    LIFESTYLE_ALIGNMENT,
    RELATIONAL_EXPECTATION,
    DIFFERENCE_TOLERANCE,
    SALIENCE_ALIGNMENT,
    CONVERSATION_ONLY
}

enum class AffinitySensitivity {
    STANDARD,
    SENSITIVE_LOW_RANKING
}

data class RankingComparisonPolicyConfig(
    val type: RankingComparisonPolicyType,
    val maxContribution: Double = 1.0,
    val sameAnswerContribution: Double = 0.4,
    val matrix: Map<String, Map<String, Double>>? = null
)

enum class RankingComparisonPolicyType {
    NONE,
    SHARED_ENGAGEMENT,
    ORDINAL_ALIGNMENT,
    SAME_ANSWER_OR_NEUTRAL_DIFFERENCE,
    CUSTOM_MATRIX
}

data class ConversationComparisonPolicyConfig(
    val type: ConversationComparisonPolicyType,
    val maxPotential: Double = 1.0,
    val sharedPotential: Double = 0.7,
    val contrastPotential: Double = 0.7,
    val minSharedValue: Double = 0.5,
    val matrix: Map<String, Map<String, ConversationMatrixCell>>? = null
)

data class ConversationMatrixCell(
    val potential: Double,
    val kind: ConversationKind
)

enum class ConversationComparisonPolicyType {
    NONE,
    SHARED_AFFINITY_CONVERSATION,
    CONSTRUCTIVE_CONTRAST_CONVERSATION,
    CUSTOM_MATRIX
}

enum class ConversationKind {
    SHARED_AFFINITY,
    CONSTRUCTIVE_CONTRAST,
    NEUTRAL,
    NOT_ELIGIBLE
}

data class AffinityAnswerSnapshot(
    val questionId: String,
    val questionSemanticVersion: Int,
    val answerCode: String
)

data class PairAffinityEvidence(
    val sharedQuestionCount: Int,
    val questionSignals: List<PairAffinityQuestionSignal>,
    val categoryEvidence: List<PairAffinityCategoryEvidence>
)

data class PairAffinityQuestionSignal(
    val questionId: String,
    val categoryId: String,
    val primaryTopic: String,
    val construct: AffinityConstruct,
    val rankingAffinityContribution: Double,
    val conversationPotential: Double,
    val conversationKind: ConversationKind,
    val sensitivity: AffinitySensitivity
)

data class PairAffinityCategoryEvidence(
    val categoryId: String,
    val sharedValidQuestionCount: Int,
    val questionSignals: List<PairAffinityQuestionSignal>,
    val rankingContributionSum: Double,
    val conversationPotentialMax: Double
)

@Component
class AffinityQuestionCatalogProvider(
    private val objectMapper: ObjectMapper,
    private val resourceLoader: ResourceLoader,
    @param:Value("\${affinity.questions.catalog:classpath:affinity-questions.es-AR.json}")
    private val catalogLocation: String
) {
    private val catalog: AffinityQuestionCatalog = loadCatalog()

    fun getCatalog(): AffinityQuestionCatalog = catalog

    private fun loadCatalog(): AffinityQuestionCatalog {
        val resource = resourceLoader.getResource(catalogLocation)
        val loaded =
            resource.inputStream.use { input ->
                objectMapper.readValue(input, AffinityQuestionCatalog::class.java)
            }

        AffinityQuestionCatalogValidator.validate(loaded)
        return loaded
    }
}

object AffinityQuestionCatalogValidator {
    fun validate(catalog: AffinityQuestionCatalog) {
        require(catalog.catalogVersion.isNotBlank()) {
            "Affinity question catalog version must not be blank"
        }
        require(catalog.categories.isNotEmpty()) {
            "Affinity question catalog must contain at least one category"
        }
        require(catalog.questions.isNotEmpty()) {
            "Affinity question catalog must contain at least one question"
        }

        requireUnique("category ids", catalog.categories.map { it.id })
        requireUnique("category display orders", catalog.categories.map { it.displayOrder.toString() })
        requireUnique("question ids", catalog.questions.map { it.id })

        val categoryIds = catalog.categories.map { it.id }.toSet()
        catalog.categories.forEach { category ->
            require(category.id.isNotBlank()) {
                "Affinity question catalog contains a blank category id"
            }
            require(category.title.isNotBlank()) {
                "Affinity category ${category.id} has blank Spanish title"
            }
            require(category.displayOrder > 0) {
                "Affinity category ${category.id} displayOrder must be positive"
            }
        }

        catalog.questions.forEach { question ->
            validateQuestion(question = question, categoryIds = categoryIds)
        }
    }

    private fun validateQuestion(
        question: AffinityQuestion,
        categoryIds: Set<String>
    ) {
        require(question.id.isNotBlank()) {
            "Affinity question catalog contains a blank question id"
        }
        require(question.semanticVersion > 0) {
            "Affinity question ${question.id} semanticVersion must be positive"
        }
        require(question.contentVersion > 0) {
            "Affinity question ${question.id} contentVersion must be positive"
        }
        require(question.categoryId in categoryIds) {
            "Affinity question ${question.id} references missing category ${question.categoryId}"
        }
        require(question.primaryTopic.isNotBlank()) {
            "Affinity question ${question.id} primaryTopic must not be blank"
        }
        require(question.prompt.isNotBlank()) {
            "Affinity question ${question.id} has blank Spanish prompt"
        }
        require(question.options.size >= 2) {
            "Affinity question ${question.id} must define at least two answer options"
        }
        requireUnique("option codes for question ${question.id}", question.options.map { it.code })
        requireUnique("option display orders for question ${question.id}", question.options.map { it.displayOrder.toString() })
        question.options.forEach { option ->
            require(option.code.isNotBlank()) {
                "Affinity question ${question.id} contains a blank option code"
            }
            require(option.label.isNotBlank()) {
                "Affinity question ${question.id} option ${option.code} has blank Spanish label"
            }
            require(option.displayOrder > 0) {
                "Affinity question ${question.id} option ${option.code} displayOrder must be positive"
            }
        }

        validateRankingPolicy(question)
        validateConversationPolicy(question)
    }

    private fun validateRankingPolicy(question: AffinityQuestion) {
        val policy = question.rankingPolicy
        require(policy.maxContribution in -1.0..1.0) {
            "Affinity question ${question.id} ranking maxContribution is outside [-1.0, 1.0]"
        }
        require(policy.sameAnswerContribution in -1.0..1.0) {
            "Affinity question ${question.id} ranking sameAnswerContribution is outside [-1.0, 1.0]"
        }
        require(question.rankingEnabled == (policy.type != RankingComparisonPolicyType.NONE)) {
            "Affinity question ${question.id} rankingEnabled must match non-NONE ranking policy"
        }

        if (!question.rankingEnabled) {
            return
        }

        when (policy.type) {
            RankingComparisonPolicyType.NONE -> error("Affinity question ${question.id} ranking is enabled with NONE policy")
            RankingComparisonPolicyType.SHARED_ENGAGEMENT,
            RankingComparisonPolicyType.ORDINAL_ALIGNMENT -> requireOptionValues(question)
            RankingComparisonPolicyType.SAME_ANSWER_OR_NEUTRAL_DIFFERENCE -> Unit
            RankingComparisonPolicyType.CUSTOM_MATRIX -> {
                val matrix = requireNotNull(policy.matrix) {
                    "Affinity question ${question.id} custom ranking matrix is missing"
                }
                validateCompleteSymmetricNumericMatrix(
                    question = question,
                    matrix = matrix,
                    label = "ranking"
                )
            }
        }
    }

    private fun validateConversationPolicy(question: AffinityQuestion) {
        val policy = question.conversationPolicy
        require(policy.maxPotential in 0.0..1.0) {
            "Affinity question ${question.id} conversation maxPotential is outside [0.0, 1.0]"
        }
        require(policy.sharedPotential in 0.0..1.0) {
            "Affinity question ${question.id} sharedPotential is outside [0.0, 1.0]"
        }
        require(policy.contrastPotential in 0.0..1.0) {
            "Affinity question ${question.id} contrastPotential is outside [0.0, 1.0]"
        }
        require(policy.minSharedValue in 0.0..1.0) {
            "Affinity question ${question.id} minSharedValue is outside [0.0, 1.0]"
        }
        require(question.conversationEnabled == (policy.type != ConversationComparisonPolicyType.NONE)) {
            "Affinity question ${question.id} conversationEnabled must match non-NONE conversation policy"
        }

        if (!question.conversationEnabled) {
            return
        }

        when (policy.type) {
            ConversationComparisonPolicyType.NONE -> error("Affinity question ${question.id} conversation is enabled with NONE policy")
            ConversationComparisonPolicyType.SHARED_AFFINITY_CONVERSATION,
            ConversationComparisonPolicyType.CONSTRUCTIVE_CONTRAST_CONVERSATION -> requireOptionValues(question)
            ConversationComparisonPolicyType.CUSTOM_MATRIX -> {
                val matrix = requireNotNull(policy.matrix) {
                    "Affinity question ${question.id} custom conversation matrix is missing"
                }
                validateCompleteSymmetricConversationMatrix(question, matrix)
            }
        }
    }

    private fun requireOptionValues(question: AffinityQuestion) {
        question.options.forEach { option ->
            val value = requireNotNull(option.value) {
                "Affinity question ${question.id} option ${option.code} requires a policy value"
            }
            require(value in 0.0..1.0) {
                "Affinity question ${question.id} option ${option.code} value is outside [0.0, 1.0]"
            }
        }
    }

    private fun validateCompleteSymmetricNumericMatrix(
        question: AffinityQuestion,
        matrix: Map<String, Map<String, Double>>,
        label: String
    ) {
        val optionCodes = question.options.map { it.code }
        val expectedOptionCodes = optionCodes.toSet()
        require(matrix.keys == expectedOptionCodes) {
            "Affinity question ${question.id} $label matrix rows must exactly match answer options"
        }
        optionCodes.forEach { left ->
            val row = matrix[left]
                ?: error("Affinity question ${question.id} $label matrix is missing row $left")
            require(row.keys == expectedOptionCodes) {
                "Affinity question ${question.id} $label matrix row $left columns must exactly match answer options"
            }
            optionCodes.forEach { right ->
                val value = row[right]
                    ?: error("Affinity question ${question.id} $label matrix is missing entry $left/$right")
                require(value in -1.0..1.0) {
                    "Affinity question ${question.id} $label matrix entry $left/$right is outside [-1.0, 1.0]"
                }
                val reverse = matrix[right]?.get(left)
                    ?: error("Affinity question ${question.id} $label matrix is missing entry $right/$left")
                require(value == reverse) {
                    "Affinity question ${question.id} $label matrix is not symmetric at $left/$right"
                }
            }
        }
    }

    private fun validateCompleteSymmetricConversationMatrix(
        question: AffinityQuestion,
        matrix: Map<String, Map<String, ConversationMatrixCell>>
    ) {
        val optionCodes = question.options.map { it.code }
        val expectedOptionCodes = optionCodes.toSet()
        require(matrix.keys == expectedOptionCodes) {
            "Affinity question ${question.id} conversation matrix rows must exactly match answer options"
        }
        optionCodes.forEach { left ->
            val row = matrix[left]
                ?: error("Affinity question ${question.id} conversation matrix is missing row $left")
            require(row.keys == expectedOptionCodes) {
                "Affinity question ${question.id} conversation matrix row $left columns must exactly match answer options"
            }
            optionCodes.forEach { right ->
                val value = row[right]
                    ?: error("Affinity question ${question.id} conversation matrix is missing entry $left/$right")
                require(value.potential in 0.0..1.0) {
                    "Affinity question ${question.id} conversation matrix entry $left/$right is outside [0.0, 1.0]"
                }
                val reverse = matrix[right]?.get(left)
                    ?: error("Affinity question ${question.id} conversation matrix is missing entry $right/$left")
                require(value == reverse) {
                    "Affinity question ${question.id} conversation matrix is not symmetric at $left/$right"
                }
            }
        }
    }

    private fun requireUnique(
        label: String,
        values: List<String>
    ) {
        val duplicates =
            values.groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(duplicates.isEmpty()) {
            "Affinity question catalog contains duplicate $label: ${duplicates.joinToString()}"
        }
    }
}

class AffinityQuestionPairEvaluator {
    fun evaluate(
        leftAnswers: Collection<AffinityAnswerSnapshot>,
        rightAnswers: Collection<AffinityAnswerSnapshot>,
        catalog: AffinityQuestionCatalog
    ): PairAffinityEvidence {
        val leftByQuestion = leftAnswers.associateBy { it.questionId }
        val rightByQuestion = rightAnswers.associateBy { it.questionId }
        val signals =
            catalog.activeQuestions.mapNotNull { question ->
                val left = leftByQuestion[question.id] ?: return@mapNotNull null
                val right = rightByQuestion[question.id] ?: return@mapNotNull null
                signalFor(question, left, right)
            }

        val categoryEvidence =
            signals.groupBy { it.categoryId }
                .map { (categoryId, categorySignals) ->
                    PairAffinityCategoryEvidence(
                        categoryId = categoryId,
                        sharedValidQuestionCount = categorySignals.size,
                        questionSignals = categorySignals,
                        rankingContributionSum = categorySignals.sumOf { it.rankingAffinityContribution },
                        conversationPotentialMax = categorySignals.maxOfOrNull { it.conversationPotential } ?: 0.0
                    )
                }
                .sortedBy { category ->
                    catalog.categoryById(category.categoryId)?.displayOrder ?: Int.MAX_VALUE
                }

        return PairAffinityEvidence(
            sharedQuestionCount = signals.size,
            questionSignals = signals,
            categoryEvidence = categoryEvidence
        )
    }

    private fun signalFor(
        question: AffinityQuestion,
        left: AffinityAnswerSnapshot,
        right: AffinityAnswerSnapshot
    ): PairAffinityQuestionSignal? {
        if (
            left.questionSemanticVersion != question.semanticVersion ||
            right.questionSemanticVersion != question.semanticVersion
        ) {
            return null
        }

        val leftOption = question.optionByCode(left.answerCode) ?: return null
        val rightOption = question.optionByCode(right.answerCode) ?: return null
        val rankingContribution =
            if (question.rankingEnabled) {
                rankingContribution(question, leftOption, rightOption)
            } else {
                0.0
            }.coerceIn(-1.0, 1.0)
        val conversation =
            if (question.conversationEnabled) {
                conversationEvidence(question, leftOption, rightOption)
            } else {
                ConversationMatrixCell(
                    potential = 0.0,
                    kind = ConversationKind.NOT_ELIGIBLE
                )
            }

        return PairAffinityQuestionSignal(
            questionId = question.id,
            categoryId = question.categoryId,
            primaryTopic = question.primaryTopic,
            construct = question.construct,
            rankingAffinityContribution = rankingContribution,
            conversationPotential = conversation.potential.coerceIn(0.0, 1.0),
            conversationKind = conversation.kind,
            sensitivity = question.sensitivity
        )
    }

    private fun rankingContribution(
        question: AffinityQuestion,
        left: AffinityAnswerOption,
        right: AffinityAnswerOption
    ): Double =
        when (val type = question.rankingPolicy.type) {
            RankingComparisonPolicyType.NONE -> 0.0
            RankingComparisonPolicyType.SHARED_ENGAGEMENT ->
                question.rankingPolicy.maxContribution * min(left.requiredValue(), right.requiredValue())
            RankingComparisonPolicyType.ORDINAL_ALIGNMENT ->
                question.rankingPolicy.maxContribution * (1.0 - (2.0 * abs(left.requiredValue() - right.requiredValue())))
            RankingComparisonPolicyType.SAME_ANSWER_OR_NEUTRAL_DIFFERENCE ->
                if (left.code == right.code) question.rankingPolicy.sameAnswerContribution else 0.0
            RankingComparisonPolicyType.CUSTOM_MATRIX ->
                question.rankingPolicy.matrix?.get(left.code)?.get(right.code)
                    ?: error("Validated $type matrix missing ${left.code}/${right.code} for ${question.id}")
        }

    private fun conversationEvidence(
        question: AffinityQuestion,
        left: AffinityAnswerOption,
        right: AffinityAnswerOption
    ): ConversationMatrixCell =
        when (val type = question.conversationPolicy.type) {
            ConversationComparisonPolicyType.NONE ->
                ConversationMatrixCell(0.0, ConversationKind.NOT_ELIGIBLE)
            ConversationComparisonPolicyType.SHARED_AFFINITY_CONVERSATION -> {
                val shared = min(left.requiredValue(), right.requiredValue())
                if (shared >= question.conversationPolicy.minSharedValue) {
                    ConversationMatrixCell(
                        potential = question.conversationPolicy.maxPotential * shared,
                        kind = ConversationKind.SHARED_AFFINITY
                    )
                } else {
                    ConversationMatrixCell(0.0, ConversationKind.NEUTRAL)
                }
            }
            ConversationComparisonPolicyType.CONSTRUCTIVE_CONTRAST_CONVERSATION -> {
                val shared = min(left.requiredValue(), right.requiredValue())
                when {
                    shared < question.conversationPolicy.minSharedValue ->
                        ConversationMatrixCell(0.0, ConversationKind.NEUTRAL)
                    left.code == right.code ->
                        ConversationMatrixCell(
                            potential = question.conversationPolicy.sharedPotential * shared,
                            kind = ConversationKind.SHARED_AFFINITY
                        )
                    else ->
                        ConversationMatrixCell(
                            potential = question.conversationPolicy.contrastPotential * shared,
                            kind = ConversationKind.CONSTRUCTIVE_CONTRAST
                        )
                }
            }
            ConversationComparisonPolicyType.CUSTOM_MATRIX ->
                question.conversationPolicy.matrix?.get(left.code)?.get(right.code)
                    ?: error("Validated $type matrix missing ${left.code}/${right.code} for ${question.id}")
        }

    private fun AffinityAnswerOption.requiredValue(): Double =
        requireNotNull(value) {
            "Affinity option $code requires a policy value"
        }
}
