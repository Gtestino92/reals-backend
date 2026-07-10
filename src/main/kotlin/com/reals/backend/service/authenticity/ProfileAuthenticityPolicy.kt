package com.reals.backend.service.authenticity

import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import org.springframework.stereotype.Component

@Component
class ProfileAuthenticityPolicy(
    private val properties: ProfileAuthenticityPolicyProperties
) {
    fun evaluate(
        request: ProfileAuthenticityVerificationRequest,
        signals: ProfileAuthenticityVerificationSignals
    ): ProfileAuthenticityVerificationResult {
        val candidatePhotoIds = request.personPhotos.map { it.photoId }.toSet()
        val comparisonPhotoIds = signals.photoComparisons.map { it.photoId }

        if (comparisonPhotoIds.size != comparisonPhotoIds.toSet().size) {
            throw MalformedProfileAuthenticitySignalsException("Duplicate profile authenticity comparison photo IDs")
        }

        val unknownPhotoIds = comparisonPhotoIds.toSet() - candidatePhotoIds
        if (unknownPhotoIds.isNotEmpty()) {
            throw MalformedProfileAuthenticitySignalsException("Unknown profile authenticity comparison photo IDs")
        }

        if (!signals.liveReferenceAccepted) {
            return needsReview(signals.provider, "authenticity-live-reference-not-accepted")
        }

        val comparisonsByPhotoId = signals.photoComparisons.associateBy { it.photoId }
        val contradictoryCount = candidatePhotoIds.count {
            comparisonsByPhotoId[it]?.outcome == ProfileAuthenticityPhotoComparisonOutcome.CONTRADICTORY
        }
        if (contradictoryCount > properties.maxContradictoryPersonPhotos) {
            return needsReview(signals.provider, "authenticity-contradiction-threshold-exceeded")
        }

        val matchedCount = candidatePhotoIds.count {
            comparisonsByPhotoId[it]?.outcome == ProfileAuthenticityPhotoComparisonOutcome.MATCHED
        }
        if (matchedCount < properties.minMatchedPersonPhotos) {
            return needsReview(signals.provider, "authenticity-insufficient-matched-person-photos")
        }

        return ProfileAuthenticityVerificationResult(
            status = ProfileAuthenticityVerificationStatus.VERIFIED,
            provider = signals.provider
        )
    }

    private fun needsReview(
        provider: String,
        reason: String
    ): ProfileAuthenticityVerificationResult =
        ProfileAuthenticityVerificationResult(
            status = ProfileAuthenticityVerificationStatus.NEEDS_REVIEW,
            provider = provider,
            reason = reason
        )
}

class MalformedProfileAuthenticitySignalsException(message: String) : RuntimeException(message)
