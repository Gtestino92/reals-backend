package com.reals.backend.service.affinity

import com.reals.backend.domain.ConversationPromptSnapshotSourceType
import com.reals.backend.service.FirstChatGuidedQuestionCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

class ConversationPromptSnapshotSelectorTest {
    private val genericCatalog =
        FirstChatGuidedQuestionCatalog(
            objectMapper = jacksonObjectMapper(),
            resourceLoader = DefaultResourceLoader(),
            catalogLocation = "classpath:first-chat-guided-questions.es.json",
            maxQuestions = 3
        )
    private val selector = ConversationPromptSnapshotSelector(genericCatalog)

    @Test
    fun `highest conversation potential is prioritized`() {
        val selected =
            selector.select(
                chatId = UUID.randomUUID(),
                maxQuestions = 3,
                catalog = catalog(
                    category("c1", 1),
                    question("q-low", "c1"),
                    question("q-high", "c2")
                ),
                evidence = evidence(
                    signal("q-low", "c1", 0.3),
                    signal("q-high", "c2", 0.9)
                )
            )

        assertEquals("q-high", selected.first().sourceQuestionId)
    }

    @Test
    fun `category diversity is applied before a second question from one category`() {
        val selected =
            selector.select(
                chatId = UUID.randomUUID(),
                maxQuestions = 3,
                catalog = catalog(
                    category("c1", 1),
                    category("c2", 2),
                    question("q1", "c1"),
                    question("q2", "c1"),
                    question("q3", "c2")
                ),
                evidence = evidence(
                    signal("q1", "c1", 0.9),
                    signal("q2", "c1", 0.8),
                    signal("q3", "c2", 0.7)
                )
            )

        assertEquals(listOf("q1", "q3", "q2"), selected.take(3).map { it.sourceQuestionId })
    }

    @Test
    fun `shared affinity and constructive contrast are eligible`() {
        val selected =
            selector.select(
                chatId = UUID.randomUUID(),
                maxQuestions = 3,
                catalog = catalog(
                    category("c1", 1),
                    category("c2", 2),
                    question("shared", "c1"),
                    question("contrast", "c2")
                ),
                evidence = evidence(
                    signal("shared", "c1", 0.8, ConversationKind.SHARED_AFFINITY),
                    signal("contrast", "c2", 0.7, ConversationKind.CONSTRUCTIVE_CONTRAST)
                )
            )

        assertEquals(listOf("shared", "contrast"), selected.take(2).map { it.sourceQuestionId })
        assertTrue(selected.take(2).all { it.sourceType == ConversationPromptSnapshotSourceType.AFFINITY })
    }

    @Test
    fun `neutral not eligible and sensitive signals are excluded`() {
        val chatId = UUID.randomUUID()
        val generic = genericCatalog.sequenceFor(chatId, 3)
        val selected =
            selector.select(
                chatId = chatId,
                maxQuestions = 3,
                catalog = catalog(
                    category("c1", 1),
                    question("neutral", "c1"),
                    question("not-eligible", "c2"),
                    question("sensitive", "c3")
                ),
                evidence = evidence(
                    signal("neutral", "c1", 1.0, ConversationKind.NEUTRAL),
                    signal("not-eligible", "c2", 1.0, ConversationKind.NOT_ELIGIBLE),
                    signal("sensitive", "c3", 1.0, sensitivity = AffinitySensitivity.SENSITIVE_LOW_RANKING)
                )
            )

        assertEquals(generic.map { it.id }, selected.map { it.sourceQuestionId })
        assertTrue(selected.all { it.sourceType == ConversationPromptSnapshotSourceType.GENERIC })
    }

    @Test
    fun `ordering and tie breaking are deterministic`() {
        val selected =
            selector.select(
                chatId = UUID.randomUUID(),
                maxQuestions = 3,
                catalog = catalog(
                    category("b", 2),
                    category("a", 1),
                    question("z", "b"),
                    question("a2", "a"),
                    question("a1", "a")
                ),
                evidence = evidence(
                    signal("z", "b", 0.5),
                    signal("a2", "a", 0.5),
                    signal("a1", "a", 0.5)
                )
            )

        assertEquals(listOf("a2", "z", "a1"), selected.take(3).map { it.sourceQuestionId })
    }

    @Test
    fun `generic prompts fill missing positions and no evidence reproduces generic sequence`() {
        val chatId = UUID.randomUUID()
        val generic = genericCatalog.sequenceFor(chatId, 3)

        val noEvidence =
            selector.select(
                chatId = chatId,
                maxQuestions = 3,
                catalog = catalog(),
                evidence = evidence()
            )

        assertEquals(generic.map { it.id }, noEvidence.map { it.sourceQuestionId })
        assertEquals(generic.map { it.text }, noEvidence.map { it.promptText })

        val oneSignal =
            selector.select(
                chatId = chatId,
                maxQuestions = 3,
                catalog = catalog(category("c1", 1), question("q1", "c1")),
                evidence = evidence(signal("q1", "c1", 1.0))
            )

        assertEquals("q1", oneSignal.first().sourceQuestionId)
        assertEquals(3, oneSignal.size)
        assertEquals(3, oneSignal.map { it.sourceQuestionId }.toSet().size)
    }

    private fun evidence(vararg signals: PairAffinityQuestionSignal): PairAffinityEvidence =
        PairAffinityEvidence(
            sharedQuestionCount = signals.size,
            questionSignals = signals.toList(),
            categoryEvidence = emptyList()
        )

    private fun signal(
        questionId: String,
        categoryId: String,
        potential: Double,
        kind: ConversationKind = ConversationKind.SHARED_AFFINITY,
        sensitivity: AffinitySensitivity = AffinitySensitivity.STANDARD
    ) = PairAffinityQuestionSignal(
        questionId = questionId,
        categoryId = categoryId,
        primaryTopic = "topic",
        construct = AffinityConstruct.VALUES_ALIGNMENT,
        rankingEligible = false,
        rankingAffinityContribution = 0.0,
        conversationPotential = potential,
        conversationKind = kind,
        sensitivity = sensitivity
    )

    private fun catalog(vararg entries: Any): AffinityQuestionCatalog {
        val categories = entries.filterIsInstance<AffinityQuestionCategory>()
        val questions = entries.filterIsInstance<AffinityQuestion>()
        val categoryIds = (categories.map { it.id } + questions.map { it.categoryId }).distinct()
        return AffinityQuestionCatalog(
            catalogVersion = "test",
            categories = categoryIds.mapIndexed { index, id ->
                categories.firstOrNull { it.id == id } ?: category(id, index + 1)
            },
            questions = questions
        )
    }

    private fun category(id: String, displayOrder: Int) =
        AffinityQuestionCategory(id = id, title = "Category $id", displayOrder = displayOrder)

    private fun question(id: String, categoryId: String) =
        AffinityQuestion(
            id = id,
            semanticVersion = 1,
            contentVersion = 1,
            status = AffinityQuestionStatus.ACTIVE,
            categoryId = categoryId,
            primaryTopic = "topic",
            construct = AffinityConstruct.VALUES_ALIGNMENT,
            answerType = AffinityAnswerType.SINGLE_CHOICE,
            prompt = "Prompt $id",
            options = listOf(
                AffinityAnswerOption("YES", "Yes", 1, 1.0),
                AffinityAnswerOption("NO", "No", 2, 0.0)
            ),
            rankingPolicy = RankingComparisonPolicyConfig(type = RankingComparisonPolicyType.NONE),
            conversationPolicy = ConversationComparisonPolicyConfig(type = ConversationComparisonPolicyType.NONE),
            sensitivity = AffinitySensitivity.STANDARD,
            rankingEnabled = false,
            conversationEnabled = false
        )
}
