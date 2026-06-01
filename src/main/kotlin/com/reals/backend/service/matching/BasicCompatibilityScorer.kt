package com.reals.backend.service.matching

import com.reals.backend.domain.Profile
import org.springframework.stereotype.Component

@Component
class BasicCompatibilityScorer(
    private val compatibilityEvaluator: CompatibilityEvaluator
) : CompatibilityScorer {

    override fun score(
        profileA: Profile,
        profileB: Profile
    ): Double =
        if (compatibilityEvaluator.compatible(profileA, profileB)) {
            1.0
        } else {
            0.0
        }
}
