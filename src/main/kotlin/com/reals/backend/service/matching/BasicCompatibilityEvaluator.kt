package com.reals.backend.service.matching

import com.reals.backend.domain.Profile
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.Period

/**
 * Basic rule-based compatibility evaluator.
 *
 * Criteria applied (all must pass):
 * 1. Gender mutual match: each user's gender is present in the other's preference set.
 * 2. Same intention: no point matching DATE with FRIENDSHIP.
 * 3. Dynamic age filters: each user's age must satisfy the other's preferred age range.
 *
 * Keep the SQL basic-compatible pair query aligned with the SQL-friendly subset
 * of these filters.
 *
 * Criteria NOT yet applied (future work):
 * - Geographic proximity: requires canonical coordinates/geohash, not free-text city/country.
 * - Interests/affinities: tag overlap score. Requires an interests field on Profile.
 * - Probabilistic scoring: replace or enrich BasicCompatibilityScorer.
 */
@Component
class BasicCompatibilityEvaluator : CompatibilityEvaluator {

    override fun compatible(profileA: Profile, profileB: Profile): Boolean {
        if (profileA.gender !in profileB.lookingForGenders) return false
        if (profileB.gender !in profileA.lookingForGenders) return false
        if (profileA.intention != profileB.intention) return false

        val today = LocalDate.now()
        if (!agePreferenceOk(viewer = profileA, candidate = profileB, today = today)) return false
        if (!agePreferenceOk(viewer = profileB, candidate = profileA, today = today)) return false
        return true
    }

    private fun agePreferenceOk(
        viewer: Profile,
        candidate: Profile,
        today: LocalDate
    ): Boolean {
        val candidateAge = Period.between(candidate.birthDate, today).years
        if (candidateAge < viewer.preferredMinAge) return false
        if (candidateAge > viewer.preferredMaxAge) return false
        return true
    }
}
