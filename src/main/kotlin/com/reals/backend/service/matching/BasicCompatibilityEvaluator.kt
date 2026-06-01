package com.reals.backend.service.matching

import com.reals.backend.domain.Gender
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.Profile
import org.springframework.stereotype.Component

/**
 * Basic rule-based compatibility evaluator.
 *
 * Criteria applied (all must pass):
 * 1. Gender mutual match: each user's gender satisfies the other's LookingForGender.
 * 2. Same intention: no point matching DATE with FRIENDSHIP.
 *
 * Keep the SQL basic-compatible pair query aligned with these cheap filters.
 *
 * Criteria NOT yet applied (future work):
 * - Geographic proximity: city match, fallback country.
 * - Interests/affinities: tag overlap score. Requires an interests field on Profile.
 * - Age range tolerance.
 * - Probabilistic scoring: replace or enrich BasicCompatibilityScorer.
 */
@Component
class BasicCompatibilityEvaluator : CompatibilityEvaluator {

    override fun compatible(profileA: Profile, profileB: Profile): Boolean {
        if (!genderMatchOk(profileA.gender, profileB.lookingForGender)) return false
        if (!genderMatchOk(profileB.gender, profileA.lookingForGender)) return false
        if (profileA.intention != profileB.intention) return false
        return true
    }

    private fun genderMatchOk(
        gender: Gender,
        lookingFor: LookingForGender
    ): Boolean =
        when (lookingFor) {
            LookingForGender.EVERYONE -> true
            LookingForGender.MEN -> gender == Gender.MALE
            LookingForGender.WOMEN -> gender == Gender.FEMALE
            LookingForGender.OTHER ->
                gender == Gender.NON_BINARY || gender == Gender.OTHER
        }
}
