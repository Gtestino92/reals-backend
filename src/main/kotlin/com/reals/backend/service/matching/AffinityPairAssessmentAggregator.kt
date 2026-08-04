package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingRankingProperties
import com.reals.backend.service.affinity.PairAffinityEvidence
import org.springframework.stereotype.Component
import kotlin.math.ln
import kotlin.math.min

data class AffinityPairAssessment(
    val sharedValidQuestionCount: Int,
    val rankingEligibleSharedQuestionCount: Int,
    val categoriesWithRankingEvidence: Int,
    val categoryAssessments: List<AffinityCategoryAssessment>,
    val overallAffinity: Double,
    val evidenceConfidence: Double,
    val relativeAdjustment: Double,
    val affinityFactor: Double,
    val affinityLogWeight: Double
)

data class AffinityCategoryAssessment(
    val categoryId: String,
    val rankingEligibleSharedQuestionCount: Int,
    val rawAffinity: Double,
    val categoryEvidenceConfidence: Double
)

@Component
class AffinityPairAssessmentAggregator(
    rankingProperties: MatchmakingRankingProperties
) {
    private val properties = rankingProperties.affinity

    fun aggregate(evidence: PairAffinityEvidence): AffinityPairAssessment {
        val categoryAssessments =
            evidence.categoryEvidence.mapNotNull { categoryEvidence ->
                val rankingSignals = categoryEvidence.questionSignals.filter { it.rankingEligible }
                if (rankingSignals.isEmpty()) {
                    null
                } else {
                    val rawAffinity =
                        rankingSignals
                            .map { it.rankingAffinityContribution }
                            .average()
                            .coerceIn(-1.0, 1.0)
                    val categoryEvidenceConfidence =
                        min(
                            1.0,
                            rankingSignals.size.toDouble() / properties.categoryFullConfidenceQuestions
                        )
                    AffinityCategoryAssessment(
                        categoryId = categoryEvidence.categoryId,
                        rankingEligibleSharedQuestionCount = rankingSignals.size,
                        rawAffinity = rawAffinity,
                        categoryEvidenceConfidence = categoryEvidenceConfidence
                    )
                }
            }

        val rankingEligibleSharedQuestionCount =
            categoryAssessments.sumOf { it.rankingEligibleSharedQuestionCount }
        val categoriesWithRankingEvidence = categoryAssessments.size

        val overallAffinity =
            if (rankingEligibleSharedQuestionCount == 0) {
                0.0
            } else {
                val confidenceSum = categoryAssessments.sumOf { it.categoryEvidenceConfidence }
                if (confidenceSum == 0.0) {
                    0.0
                } else {
                    (
                        categoryAssessments.sumOf { it.rawAffinity * it.categoryEvidenceConfidence } /
                            confidenceSum
                        ).coerceIn(-1.0, 1.0)
                }
            }

        val evidenceConfidence =
            if (rankingEligibleSharedQuestionCount == 0) {
                0.0
            } else {
                val sharedQuestionConfidence =
                    min(
                        1.0,
                        rankingEligibleSharedQuestionCount.toDouble() / properties.fullConfidenceSharedQuestions
                    )
                val categoryBreadthConfidence =
                    min(
                        1.0,
                        categoriesWithRankingEvidence.toDouble() / properties.fullConfidenceCategories
                    )
                min(sharedQuestionConfidence, categoryBreadthConfidence)
            }

        val relativeAdjustment =
            properties.maxRelativeAdjustment * evidenceConfidence * overallAffinity
        val affinityFactor = 1.0 + relativeAdjustment
        val affinityLogWeight = ln(affinityFactor)

        requireFinite(overallAffinity, "overallAffinity")
        requireFinite(evidenceConfidence, "evidenceConfidence")
        requireFinite(relativeAdjustment, "relativeAdjustment")
        requireFinite(affinityFactor, "affinityFactor")
        requireFinite(affinityLogWeight, "affinityLogWeight")

        return AffinityPairAssessment(
            sharedValidQuestionCount = evidence.sharedQuestionCount,
            rankingEligibleSharedQuestionCount = rankingEligibleSharedQuestionCount,
            categoriesWithRankingEvidence = categoriesWithRankingEvidence,
            categoryAssessments = categoryAssessments,
            overallAffinity = overallAffinity,
            evidenceConfidence = evidenceConfidence,
            relativeAdjustment = relativeAdjustment,
            affinityFactor = affinityFactor,
            affinityLogWeight = affinityLogWeight
        )
    }

    private fun requireFinite(
        value: Double,
        name: String
    ) {
        require(value.isFinite()) {
            "Affinity pair assessment $name must be finite"
        }
    }
}
