package com.reals.backend.service.authenticity

import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ProfileAuthenticityPolicyTest {

    @Test
    fun `accepted live reference with exactly minimum matched and zero contradictory is verified`() {
        val ids = photoIds(3)

        val result = evaluate(
            candidateIds = ids,
            comparisons = comparisons(ids, ProfileAuthenticityPhotoComparisonOutcome.MATCHED)
        )

        assertEquals(ProfileAuthenticityVerificationStatus.VERIFIED, result.status)
    }

    @Test
    fun `accepted live reference with more than minimum matched and zero contradictory is verified`() {
        val ids = photoIds(4)

        val result = evaluate(
            candidateIds = ids,
            comparisons = comparisons(ids, ProfileAuthenticityPhotoComparisonOutcome.MATCHED)
        )

        assertEquals(ProfileAuthenticityVerificationStatus.VERIFIED, result.status)
    }

    @Test
    fun `accepted live reference with minimum matched and unresolved photos is verified`() {
        val matched = photoIds(3)
        val unresolved = photoIds(2)

        val result = evaluate(
            candidateIds = matched + unresolved,
            comparisons = comparisons(matched, ProfileAuthenticityPhotoComparisonOutcome.MATCHED) +
                comparisons(unresolved, ProfileAuthenticityPhotoComparisonOutcome.UNRESOLVED)
        )

        assertEquals(ProfileAuthenticityVerificationStatus.VERIFIED, result.status)
    }

    @Test
    fun `fewer than minimum matched with zero contradictory needs review`() {
        val ids = photoIds(3)

        val result = evaluate(
            candidateIds = ids,
            comparisons = comparisons(ids.take(2), ProfileAuthenticityPhotoComparisonOutcome.MATCHED)
        )

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("authenticity-insufficient-matched-person-photos", result.reason)
    }

    @Test
    fun `exactly zero contradictions with default max zero is allowed`() {
        val ids = photoIds(3)

        val result = evaluate(
            candidateIds = ids,
            comparisons = comparisons(ids, ProfileAuthenticityPhotoComparisonOutcome.MATCHED)
        )

        assertEquals(ProfileAuthenticityVerificationStatus.VERIFIED, result.status)
    }

    @Test
    fun `one contradiction with max zero needs review`() {
        val matched = photoIds(3)
        val contradiction = UUID.randomUUID()

        val result = evaluate(
            candidateIds = matched + contradiction,
            comparisons = comparisons(matched, ProfileAuthenticityPhotoComparisonOutcome.MATCHED) +
                ProfileAuthenticityPhotoComparison(
                    photoId = contradiction,
                    outcome = ProfileAuthenticityPhotoComparisonOutcome.CONTRADICTORY
                )
        )

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("authenticity-contradiction-threshold-exceeded", result.reason)
    }

    @Test
    fun `many matches plus one contradiction with max zero still needs review`() {
        val matched = photoIds(6)
        val contradiction = UUID.randomUUID()

        val result = evaluate(
            candidateIds = matched + contradiction,
            comparisons = comparisons(matched, ProfileAuthenticityPhotoComparisonOutcome.MATCHED) +
                ProfileAuthenticityPhotoComparison(
                    photoId = contradiction,
                    outcome = ProfileAuthenticityPhotoComparisonOutcome.CONTRADICTORY
                )
        )

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("authenticity-contradiction-threshold-exceeded", result.reason)
    }

    @Test
    fun `configurable max one contradiction is allowed when minimum matched is satisfied`() {
        val matched = photoIds(3)
        val contradiction = UUID.randomUUID()

        val result = evaluate(
            candidateIds = matched + contradiction,
            comparisons = comparisons(matched, ProfileAuthenticityPhotoComparisonOutcome.MATCHED) +
                ProfileAuthenticityPhotoComparison(
                    photoId = contradiction,
                    outcome = ProfileAuthenticityPhotoComparisonOutcome.CONTRADICTORY
                ),
            maxContradictoryPersonPhotos = 1
        )

        assertEquals(ProfileAuthenticityVerificationStatus.VERIFIED, result.status)
    }

    @Test
    fun `configurable max one contradiction and two contradictions needs review`() {
        val matched = photoIds(3)
        val contradictions = photoIds(2)

        val result = evaluate(
            candidateIds = matched + contradictions,
            comparisons = comparisons(matched, ProfileAuthenticityPhotoComparisonOutcome.MATCHED) +
                comparisons(contradictions, ProfileAuthenticityPhotoComparisonOutcome.CONTRADICTORY),
            maxContradictoryPersonPhotos = 1
        )

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("authenticity-contradiction-threshold-exceeded", result.reason)
    }

    @Test
    fun `live reference not accepted needs review`() {
        val ids = photoIds(3)

        val result = evaluate(
            candidateIds = ids,
            comparisons = comparisons(ids, ProfileAuthenticityPhotoComparisonOutcome.MATCHED),
            liveReferenceAccepted = false
        )

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("authenticity-live-reference-not-accepted", result.reason)
    }

    @Test
    fun `unresolved outcomes do not increment matched count`() {
        val matched = photoIds(2)
        val unresolved = UUID.randomUUID()

        val result = evaluate(
            candidateIds = matched + unresolved,
            comparisons = comparisons(matched, ProfileAuthenticityPhotoComparisonOutcome.MATCHED) +
                ProfileAuthenticityPhotoComparison(
                    photoId = unresolved,
                    outcome = ProfileAuthenticityPhotoComparisonOutcome.UNRESOLVED
                )
        )

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("authenticity-insufficient-matched-person-photos", result.reason)
    }

    @Test
    fun `unresolved outcomes do not increment contradictory count`() {
        val matched = photoIds(3)
        val unresolved = UUID.randomUUID()

        val result = evaluate(
            candidateIds = matched + unresolved,
            comparisons = comparisons(matched, ProfileAuthenticityPhotoComparisonOutcome.MATCHED) +
                ProfileAuthenticityPhotoComparison(
                    photoId = unresolved,
                    outcome = ProfileAuthenticityPhotoComparisonOutcome.UNRESOLVED
                )
        )

        assertEquals(ProfileAuthenticityVerificationStatus.VERIFIED, result.status)
    }

    @Test
    fun `missing candidate comparison is treated as unresolved`() {
        val matched = photoIds(2)
        val missing = UUID.randomUUID()

        val result = evaluate(
            candidateIds = matched + missing,
            comparisons = comparisons(matched, ProfileAuthenticityPhotoComparisonOutcome.MATCHED)
        )

        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, result.status)
        assertEquals("authenticity-insufficient-matched-person-photos", result.reason)
    }

    @Test
    fun `duplicate comparison photo IDs are malformed provider output`() {
        val duplicateId = UUID.randomUUID()
        val policy = ProfileAuthenticityPolicy(ProfileAuthenticityPolicyProperties())

        assertThrows<MalformedProfileAuthenticitySignalsException> {
            policy.evaluate(
                request(listOf(duplicateId)),
                signals(
                    listOf(
                        ProfileAuthenticityPhotoComparison(
                            photoId = duplicateId,
                            outcome = ProfileAuthenticityPhotoComparisonOutcome.MATCHED
                        ),
                        ProfileAuthenticityPhotoComparison(
                            photoId = duplicateId,
                            outcome = ProfileAuthenticityPhotoComparisonOutcome.UNRESOLVED
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `unknown comparison photo IDs are malformed provider output`() {
        val policy = ProfileAuthenticityPolicy(ProfileAuthenticityPolicyProperties())

        assertThrows<MalformedProfileAuthenticitySignalsException> {
            policy.evaluate(
                request(photoIds(3)),
                signals(
                    listOf(
                        ProfileAuthenticityPhotoComparison(
                            photoId = UUID.randomUUID(),
                            outcome = ProfileAuthenticityPhotoComparisonOutcome.MATCHED
                        )
                    )
                )
            )
        }
    }

    private fun evaluate(
        candidateIds: List<UUID>,
        comparisons: List<ProfileAuthenticityPhotoComparison>,
        liveReferenceAccepted: Boolean = true,
        minMatchedPersonPhotos: Int = 3,
        maxContradictoryPersonPhotos: Int = 0
    ): ProfileAuthenticityVerificationResult =
        ProfileAuthenticityPolicy(
            ProfileAuthenticityPolicyProperties(
                minMatchedPersonPhotos = minMatchedPersonPhotos,
                maxContradictoryPersonPhotos = maxContradictoryPersonPhotos
            )
        ).evaluate(
            request(candidateIds),
            signals(
                comparisons = comparisons,
                liveReferenceAccepted = liveReferenceAccepted
            )
        )

    private fun request(candidateIds: List<UUID>): ProfileAuthenticityVerificationRequest =
        ProfileAuthenticityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            personPhotos = candidateIds.mapIndexed { index, photoId ->
                ProfileAuthenticityPhotoCandidate(
                    photoId = photoId,
                    photoVersion = index.toLong(),
                    storageKey = "profile/$photoId.jpg"
                )
            }
        )

    private fun signals(
        comparisons: List<ProfileAuthenticityPhotoComparison>,
        liveReferenceAccepted: Boolean = true
    ): ProfileAuthenticityVerificationSignals =
        ProfileAuthenticityVerificationSignals(
            provider = "test",
            liveReferenceAccepted = liveReferenceAccepted,
            photoComparisons = comparisons
        )

    private fun photoIds(count: Int): List<UUID> =
        (1..count).map { UUID.randomUUID() }

    private fun comparisons(
        photoIds: List<UUID>,
        outcome: ProfileAuthenticityPhotoComparisonOutcome
    ): List<ProfileAuthenticityPhotoComparison> =
        photoIds.map { ProfileAuthenticityPhotoComparison(photoId = it, outcome = outcome) }
}
