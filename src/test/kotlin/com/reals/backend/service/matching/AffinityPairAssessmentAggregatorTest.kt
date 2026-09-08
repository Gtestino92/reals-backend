package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingAffinityRankingProperties
import com.reals.backend.config.MatchmakingRankingProperties
import com.reals.backend.service.affinity.AffinityAnswerOption
import com.reals.backend.service.affinity.AffinityAnswerSnapshot
import com.reals.backend.service.affinity.AffinityAnswerType
import com.reals.backend.service.affinity.AffinityConstruct
import com.reals.backend.service.affinity.AffinityQuestion
import com.reals.backend.service.affinity.AffinityQuestionCatalog
import com.reals.backend.service.affinity.AffinityQuestionCategory
import com.reals.backend.service.affinity.AffinityQuestionPairEvaluator
import com.reals.backend.service.affinity.AffinityQuestionStatus
import com.reals.backend.service.affinity.AffinitySensitivity
import com.reals.backend.service.affinity.ConversationComparisonPolicyConfig
import com.reals.backend.service.affinity.ConversationComparisonPolicyType
import com.reals.backend.service.affinity.PairAffinityCategoryEvidence
import com.reals.backend.service.affinity.PairAffinityEvidence
import com.reals.backend.service.affinity.PairAffinityQuestionSignal
import com.reals.backend.service.affinity.RankingComparisonPolicyConfig
import com.reals.backend.service.affinity.RankingComparisonPolicyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AffinityPairAssessmentAggregatorTest {

    @Test
    fun `no evidence produces exact neutral output`() {
        val assessment = aggregator().aggregate(evidence(emptyList()))

        assertEquals(0, assessment.sharedValidQuestionCount)
        assertEquals(0, assessment.rankingEligibleSharedQuestionCount)
        assertEquals(0, assessment.categoriesWithRankingEvidence)
        assertEquals(0.0, assessment.overallAffinity)
        assertEquals(0.0, assessment.evidenceConfidence)
        assertEquals(0.0, assessment.relativeAdjustment)
        assertEquals(1.0, assessment.affinityFactor)
        assertEquals(0.0, assessment.affinityLogWeight)
    }

    @Test
    fun `one ranking enabled shared question produces low confidence`() {
        val assessment = aggregator().aggregate(evidence(listOf(signal("c1", 1.0))))

        assertEquals(1, assessment.rankingEligibleSharedQuestionCount)
        assertEquals(1, assessment.categoriesWithRankingEvidence)
        assertEquals(1.0 / 12.0, assessment.evidenceConfidence)
        assertEquals(1.0 + 0.10 * (1.0 / 12.0), assessment.affinityFactor)
    }

    @Test
    fun `twelve shared questions across four categories produce full default confidence`() {
        val signals =
            (1..4).flatMap { category ->
                (1..3).map { signal("c$category", 1.0, questionId = "q$category-$it") }
            }

        val assessment = aggregator().aggregate(evidence(signals))

        assertEquals(12, assessment.rankingEligibleSharedQuestionCount)
        assertEquals(4, assessment.categoriesWithRankingEvidence)
        assertEquals(1.0, assessment.evidenceConfidence)
        assertEquals(1.10, assessment.affinityFactor)
    }

    @Test
    fun `twelve shared questions from one category do not produce full confidence`() {
        val signals = (1..12).map { signal("c1", 1.0, questionId = "q$it") }

        val assessment = aggregator().aggregate(evidence(signals))

        assertEquals(12, assessment.rankingEligibleSharedQuestionCount)
        assertEquals(1, assessment.categoriesWithRankingEvidence)
        assertEquals(0.25, assessment.evidenceConfidence)
    }

    @Test
    fun `categories are averaged rather than summed`() {
        val assessment =
            aggregator().aggregate(
                evidence(
                    listOf(
                        signal("positive", 1.0),
                        signal("negative", -1.0)
                    )
                )
            )

        assertEquals(0.0, assessment.overallAffinity)
    }

    @Test
    fun `category with many questions does not dominate another category solely by count`() {
        val manyPositive = (1..9).map { signal("many", 1.0, questionId = "many-$it") }
        val cappedNegative = (1..3).map { signal("capped", -1.0, questionId = "capped-$it") }

        val assessment = aggregator().aggregate(evidence(manyPositive + cappedNegative))

        assertEquals(0.0, assessment.overallAffinity)
    }

    @Test
    fun `positive and negative category signals combine correctly`() {
        val assessment =
            aggregator().aggregate(
                evidence(
                    listOf(
                        signal("positive", 0.5),
                        signal("negative", -0.25)
                    )
                )
            )

        assertEquals(0.125, assessment.overallAffinity)
    }

    @Test
    fun `ranking disabled questions do not affect counts or scores`() {
        val assessment =
            aggregator().aggregate(
                evidence(
                    listOf(
                        signal("c1", 1.0, rankingEligible = false),
                        signal("c2", -1.0, rankingEligible = false)
                    )
                )
            )

        assertEquals(2, assessment.sharedValidQuestionCount)
        assertEquals(0, assessment.rankingEligibleSharedQuestionCount)
        assertEquals(0.0, assessment.overallAffinity)
        assertEquals(1.0, assessment.affinityFactor)
    }

    @Test
    fun `semantic version mismatches remain excluded`() {
        val catalog = catalog(question("q1", "c1", rankingEnabled = true))
        val evidence =
            AffinityQuestionPairEvaluator().evaluate(
                leftAnswers = listOf(AffinityAnswerSnapshot("q1", 1, "YES")),
                rightAnswers = listOf(AffinityAnswerSnapshot("q1", 2, "YES")),
                catalog = catalog
            )

        val assessment = aggregator().aggregate(evidence)

        assertEquals(0, assessment.sharedValidQuestionCount)
        assertEquals(1.0, assessment.affinityFactor)
    }

    @Test
    fun `result is symmetric for A B and B A`() {
        val catalog = catalog(question("q1", "c1", rankingEnabled = true))
        val evaluator = AffinityQuestionPairEvaluator()
        val left = listOf(AffinityAnswerSnapshot("q1", 1, "YES"))
        val right = listOf(AffinityAnswerSnapshot("q1", 1, "NO"))

        val leftRight = aggregator().aggregate(evaluator.evaluate(left, right, catalog))
        val rightLeft = aggregator().aggregate(evaluator.evaluate(right, left, catalog))

        assertEquals(leftRight.overallAffinity, rightLeft.overallAffinity)
        assertEquals(leftRight.affinityFactor, rightLeft.affinityFactor)
    }

    @Test
    fun `all values remain finite and within declared ranges`() {
        val assessment =
            aggregator().aggregate(
                evidence(
                    listOf(
                        signal("c1", 1.0),
                        signal("c2", -1.0)
                    )
                )
            )

        assertTrue(assessment.overallAffinity.isFinite())
        assertTrue(assessment.evidenceConfidence in 0.0..1.0)
        assertTrue(assessment.relativeAdjustment in -0.10..0.10)
        assertTrue(assessment.affinityFactor in 0.90..1.10)
        assertTrue(assessment.affinityLogWeight.isFinite())
    }

    @Test
    fun `default factor remains within point ninety to one point ten`() {
        val negative = aggregator().aggregate(evidence((1..12).map { signal("c${(it - 1) / 3}", -1.0, "n$it") }))
        val positive = aggregator().aggregate(evidence((1..12).map { signal("c${(it - 1) / 3}", 1.0, "p$it") }))

        assertTrue(negative.affinityFactor >= 0.90)
        assertTrue(positive.affinityFactor <= 1.10)
    }

    @Test
    fun `custom maximum adjustment changes the bound correctly`() {
        val assessment =
            aggregator(maxRelativeAdjustment = 0.25)
                .aggregate(evidence((1..12).map { signal("c${(it - 1) / 3}", 1.0, "q$it") }))

        assertEquals(1.25, assessment.affinityFactor)
        assertTrue(abs(assessment.affinityLogWeight - kotlin.math.ln(1.25)) < 0.0000001)
    }

    private fun aggregator(maxRelativeAdjustment: Double = 0.10): AffinityPairAssessmentAggregator =
        AffinityPairAssessmentAggregator(
            MatchmakingRankingProperties(
                affinity = MatchmakingAffinityRankingProperties(
                    maxRelativeAdjustment = maxRelativeAdjustment
                )
            )
        )

    private fun evidence(signals: List<PairAffinityQuestionSignal>): PairAffinityEvidence =
        PairAffinityEvidence(
            sharedQuestionCount = signals.size,
            questionSignals = signals,
            categoryEvidence =
                signals.groupBy { it.categoryId }.map { (categoryId, categorySignals) ->
                    PairAffinityCategoryEvidence(
                        categoryId = categoryId,
                        sharedValidQuestionCount = categorySignals.size,
                        questionSignals = categorySignals,
                        rankingContributionSum = categorySignals.sumOf { it.rankingAffinityContribution },
                        conversationPotentialMax = 0.0
                    )
                }
        )

    private fun signal(
        categoryId: String,
        contribution: Double,
        questionId: String = "q-$categoryId",
        rankingEligible: Boolean = true
    ): PairAffinityQuestionSignal =
        PairAffinityQuestionSignal(
            questionId = questionId,
            categoryId = categoryId,
            primaryTopic = "topic",
            construct = AffinityConstruct.VALUES_ALIGNMENT,
            rankingEligible = rankingEligible,
            rankingAffinityContribution = contribution,
            conversationPotential = 0.0,
            conversationKind = com.reals.backend.service.affinity.ConversationKind.NOT_ELIGIBLE,
            sensitivity = AffinitySensitivity.STANDARD
        )

    private fun catalog(question: AffinityQuestion): AffinityQuestionCatalog =
        AffinityQuestionCatalog(
            catalogVersion = "test",
            categories = listOf(AffinityQuestionCategory(question.categoryId, "Category", displayOrder = 1)),
            questions = listOf(question)
        )

    private fun question(
        id: String,
        categoryId: String,
        rankingEnabled: Boolean
    ): AffinityQuestion =
        AffinityQuestion(
            id = id,
            semanticVersion = 1,
            contentVersion = 1,
            status = AffinityQuestionStatus.ACTIVE,
            categoryId = categoryId,
            primaryTopic = "topic",
            construct = AffinityConstruct.VALUES_ALIGNMENT,
            answerType = AffinityAnswerType.SINGLE_CHOICE,
            prompt = "Question",
            options = listOf(
                AffinityAnswerOption("YES", "Yes", 1),
                AffinityAnswerOption("NO", "No", 2)
            ),
            rankingPolicy =
                if (rankingEnabled) {
                    RankingComparisonPolicyConfig(
                        type = RankingComparisonPolicyType.CUSTOM_MATRIX,
                        matrix = mapOf(
                            "YES" to mapOf("YES" to 1.0, "NO" to -0.5),
                            "NO" to mapOf("YES" to -0.5, "NO" to 1.0)
                        )
                    )
                } else {
                    RankingComparisonPolicyConfig(type = RankingComparisonPolicyType.NONE)
                },
            conversationPolicy = ConversationComparisonPolicyConfig(type = ConversationComparisonPolicyType.NONE),
            sensitivity = AffinitySensitivity.STANDARD,
            rankingEnabled = rankingEnabled,
            conversationEnabled = false
        )
}
