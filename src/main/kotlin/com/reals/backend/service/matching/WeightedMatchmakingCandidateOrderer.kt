package com.reals.backend.service.matching

import com.reals.backend.domain.MatchmakingPartnerCandidate
import org.springframework.stereotype.Component
import kotlin.math.ln

data class WeightedMatchmakingPartnerCandidate(
    val candidate: MatchmakingPartnerCandidate,
    val logWeight: Double,
    val order: Int
)

@Component
class WeightedMatchmakingCandidateOrderer(
    private val randomSource: MatchmakingRandomSource
) {

    fun order(
        candidates: List<WeightedMatchmakingPartnerCandidate>
    ): List<WeightedMatchmakingPartnerCandidate> =
        candidates
            .map { candidate ->
                CandidatePriority(
                    candidate = candidate,
                    priority = candidate.logWeight + gumbelNoise()
                )
            }
            .sortedWith(
                compareByDescending<CandidatePriority> { it.priority }
                    .thenBy { it.candidate.order }
            )
            .map { it.candidate }

    private fun gumbelNoise(): Double {
        val raw = randomSource.nextUnitDouble()
        val unit =
            if (raw.isFinite()) {
                raw.coerceIn(MIN_OPEN_UNIT, MAX_OPEN_UNIT)
            } else {
                MIN_OPEN_UNIT
            }
        return -ln(-ln(unit))
    }

    private data class CandidatePriority(
        val candidate: WeightedMatchmakingPartnerCandidate,
        val priority: Double
    )

    private companion object {
        const val MIN_OPEN_UNIT = Double.MIN_VALUE
        const val MAX_OPEN_UNIT = 1.0 - 1.0e-16
    }
}
