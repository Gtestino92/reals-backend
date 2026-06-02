package com.reals.backend.service.matching

import com.reals.backend.domain.Gender
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.Profile
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.Period

/**
 * Basic rule-based compatibility evaluator.
 *
 * Criteria applied (all must pass):
 * 1. Gender mutual match: each user's gender satisfies the other's LookingForGender.
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
        if (!genderMatchOk(profileA.gender, profileB.lookingForGender)) return false
        if (!genderMatchOk(profileB.gender, profileA.lookingForGender)) return false
        if (profileA.intention != profileB.intention) return false

        val today = LocalDate.now()
        if (!agePreferenceOk(viewer = profileA, candidate = profileB, today = today)) return false
        if (!agePreferenceOk(viewer = profileB, candidate = profileA, today = today)) return false
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

    private fun agePreferenceOk(
        viewer: Profile,
        candidate: Profile,
        today: LocalDate
    ): Boolean {
        val candidateAge = Period.between(candidate.birthDate, today).years
        val minAge = viewer.preferredMinAge
        val maxAge = viewer.preferredMaxAge

        if (minAge != null && candidateAge < minAge) return false
        if (maxAge != null && candidateAge > maxAge) return false
        return true
    }
}
