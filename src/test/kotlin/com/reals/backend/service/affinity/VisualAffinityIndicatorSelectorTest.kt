package com.reals.backend.service.affinity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VisualAffinityIndicatorSelectorTest {
    private val selector = VisualAffinityIndicatorSelector()

    @Test
    fun `shared affinity creates a positive category indicator`() {
        val selected =
            selector.select(
                catalog = catalog(category("music", "Música", 1)),
                evidence = evidence(signal("q1", "music", 0.8))
            )

        assertEquals(listOf("music"), selected.map { it.categoryId })
        assertEquals("Música", selected.single().categoryTitle)
    }

    @Test
    fun `contrast neutral and sensitive evidence do not create indicators`() {
        val selected =
            selector.select(
                catalog = catalog(
                    category("contrast", "Contraste", 1),
                    category("neutral", "Neutral", 2),
                    category("sensitive", "Sensible", 3)
                ),
                evidence = evidence(
                    signal("q1", "contrast", 1.0, ConversationKind.CONSTRUCTIVE_CONTRAST),
                    signal("q2", "neutral", 1.0, ConversationKind.NEUTRAL),
                    signal("q3", "sensitive", 1.0, sensitivity = AffinitySensitivity.SENSITIVE_LOW_RANKING)
                )
            )

        assertEquals(emptyList<VisualAffinityIndicatorSelection>(), selected)
    }

    @Test
    fun `multiple questions in one category create one row`() {
        val selected =
            selector.select(
                catalog = catalog(category("music", "Música", 1)),
                evidence = evidence(
                    signal("q1", "music", 0.5),
                    signal("q2", "music", 0.9)
                )
            )

        assertEquals(1, selected.size)
        assertEquals("music", selected.single().categoryId)
    }

    @Test
    fun `maximum potential controls internal category order`() {
        val selected =
            selector.select(
                catalog = catalog(
                    category("low", "Baja", 1),
                    category("high", "Alta", 2)
                ),
                evidence = evidence(
                    signal("q1", "low", 0.4),
                    signal("q2", "high", 0.8)
                )
            )

        assertEquals(listOf("high", "low"), selected.map { it.categoryId })
    }

    @Test
    fun `catalog display order resolves ties and output is capped at three`() {
        val selected =
            selector.select(
                catalog = catalog(
                    category("c4", "C4", 4),
                    category("c2", "C2", 2),
                    category("c1", "C1", 1),
                    category("c3", "C3", 3)
                ),
                evidence = evidence(
                    signal("q4", "c4", 0.7),
                    signal("q2", "c2", 0.7),
                    signal("q1", "c1", 0.7),
                    signal("q3", "c3", 0.7)
                )
            )

        assertEquals(listOf("c1", "c2", "c3"), selected.map { it.categoryId })
    }

    @Test
    fun `no evidence produces an empty list`() {
        assertEquals(emptyList<VisualAffinityIndicatorSelection>(), selector.select(catalog(), evidence()))
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

    private fun catalog(vararg categories: AffinityQuestionCategory) =
        AffinityQuestionCatalog(
            catalogVersion = "test",
            categories = categories.toList(),
            questions = emptyList()
        )

    private fun category(id: String, title: String, displayOrder: Int) =
        AffinityQuestionCategory(id = id, title = title, displayOrder = displayOrder)
}
