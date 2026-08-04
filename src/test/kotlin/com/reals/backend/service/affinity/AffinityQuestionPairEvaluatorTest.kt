package com.reals.backend.service.affinity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.math.abs

class AffinityQuestionPairEvaluatorTest {
    private val catalog = loadCatalog()
    private val evaluator = AffinityQuestionPairEvaluator()

    @Test
    fun `no shared answers produces empty evidence not incompatibility`() {
        val evidence =
            evaluator.evaluate(
                leftAnswers = listOf(answer("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT")),
                rightAnswers = listOf(answer("MUSIC_IMPORTANCE_001", "VERY_IMPORTANT")),
                catalog = catalog
            )

        assertEquals(0, evidence.sharedQuestionCount)
        assertTrue(evidence.questionSignals.isEmpty())
        assertTrue(evidence.categoryEvidence.isEmpty())
    }

    @Test
    fun `shared strong interest produces positive domain evidence`() {
        val evidence =
            evaluator.evaluate(
                leftAnswers = listOf(answer("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT")),
                rightAnswers = listOf(answer("CINEMA_IMPORTANCE_001", "IMPORTANT")),
                catalog = catalog
            )

        val signal = evidence.questionSignals.single()
        assertTrue(signal.rankingAffinityContribution > 0.0)
        assertEquals(ConversationKind.SHARED_AFFINITY, signal.conversationKind)
    }

    @Test
    fun `same low interest does not produce large reward`() {
        val evidence =
            evaluator.evaluate(
                leftAnswers = listOf(answer("CINEMA_IMPORTANCE_001", "NOT_FOR_ME")),
                rightAnswers = listOf(answer("CINEMA_IMPORTANCE_001", "NOT_FOR_ME")),
                catalog = catalog
            )

        assertTrue(evidence.questionSignals.single().rankingAffinityContribution <= 0.05)
    }

    @Test
    fun `different taste with shared domain interest is not negative overall`() {
        val evidence =
            evaluator.evaluate(
                leftAnswers = listOf(
                    answer("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT"),
                    answer("CINEMA_TASTE_001", "COMFORT_STORIES")
                ),
                rightAnswers = listOf(
                    answer("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT"),
                    answer("CINEMA_TASTE_001", "MYSTERY_TENSION")
                ),
                catalog = catalog
            )

        val tasteSignal = evidence.questionSignals.first { it.questionId == "CINEMA_TASTE_001" }
        assertTrue(tasteSignal.rankingAffinityContribution >= 0.0)
        assertEquals(ConversationKind.CONSTRUCTIVE_CONTRAST, tasteSignal.conversationKind)
        assertTrue(evidence.questionSignals.sumOf { it.rankingAffinityContribution } > 0.0)
    }

    @Test
    fun `different taste may produce constructive conversation potential`() {
        val evidence =
            evaluator.evaluate(
                leftAnswers = listOf(answer("MUSIC_MOOD_001", "ENERGETIC")),
                rightAnswers = listOf(answer("MUSIC_MOOD_001", "CHILL")),
                catalog = catalog
            )

        val signal = evidence.questionSignals.single()
        assertEquals(ConversationKind.CONSTRUCTIVE_CONTRAST, signal.conversationKind)
        assertTrue(signal.conversationPotential > 0.0)
    }

    @Test
    fun `alignment relevant ordinal extremes can produce negative evidence`() {
        val evidence =
            evaluator.evaluate(
                leftAnswers = listOf(answer("COMMUNICATION_FREQUENCY_001", "LOW")),
                rightAnswers = listOf(answer("COMMUNICATION_FREQUENCY_001", "VERY_HIGH")),
                catalog = catalog
            )

        assertTrue(evidence.questionSignals.single().rankingAffinityContribution < 0.0)
    }

    @Test
    fun `semantic version mismatch is ignored safely`() {
        val evidence =
            evaluator.evaluate(
                leftAnswers = listOf(answer("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT", semanticVersion = 0)),
                rightAnswers = listOf(answer("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT")),
                catalog = catalog
            )

        assertEquals(0, evidence.sharedQuestionCount)
        assertTrue(evidence.questionSignals.isEmpty())
    }

    @Test
    fun `symmetry evaluating A B equals B A`() {
        val left =
            listOf(
                answer("SOCIAL_ENERGY_001", "QUIET_ONE_ON_ONE"),
                answer("PACE_EXPECTATION_001", "SLOW")
            )
        val right =
            listOf(
                answer("SOCIAL_ENERGY_001", "GROUP_ENERGY"),
                answer("PACE_EXPECTATION_001", "DIRECT")
            )

        val leftRight = evaluator.evaluate(left, right, catalog)
        val rightLeft = evaluator.evaluate(right, left, catalog)

        assertEquals(leftRight, rightLeft)
    }

    @Test
    fun `all values remain within declared ranges`() {
        val allAnswers =
            catalog.activeQuestions.map { question ->
                answer(question.id, question.options.last().code)
            }

        val evidence = evaluator.evaluate(allAnswers, allAnswers, catalog)

        evidence.questionSignals.forEach { signal ->
            assertTrue(signal.rankingAffinityContribution in -1.0..1.0)
            assertTrue(signal.conversationPotential in 0.0..1.0)
            assertTrue(abs(signal.rankingAffinityContribution) <= 1.0)
        }
    }

    private fun answer(
        questionId: String,
        answerCode: String,
        semanticVersion: Int = 1
    ): AffinityAnswerSnapshot =
        AffinityAnswerSnapshot(
            questionId = questionId,
            questionSemanticVersion = semanticVersion,
            answerCode = answerCode
        )

    private fun loadCatalog(): AffinityQuestionCatalog {
        val input =
            requireNotNull(
                javaClass.classLoader.getResourceAsStream("affinity-questions.es-AR.json")
            )
        return input.use {
            jacksonObjectMapper().readValue(it, AffinityQuestionCatalog::class.java)
        }
    }
}
