package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoModerationStatus
import org.springframework.stereotype.Component

@Component
class ProfilePhotoModerationPolicy(
    private val properties: ProfilePhotoModerationPolicyProperties
) {
    fun evaluate(signals: ProfilePhotoAnalysisSignals): PhotoModerationResult {
        val moderation = signals.moderation

        if (rejects(moderation)) {
            return PhotoModerationResult(
                status = PhotoModerationStatus.REJECTED,
                provider = signals.provider,
                reason = "photo-moderation-rejected"
            )
        }

        if (needsReview(moderation)) {
            return PhotoModerationResult(
                status = PhotoModerationStatus.NEEDS_REVIEW,
                provider = signals.provider,
                reason = "photo-moderation-needs-review"
            )
        }

        return PhotoModerationResult(
            status = PhotoModerationStatus.APPROVED,
            provider = signals.provider
        )
    }

    private fun rejects(signals: ProfilePhotoModerationSignals): Boolean =
        listOf(
            signals.sexualExplicit to properties.sexualExplicit.rejectThreshold,
            signals.violenceOrThreat to properties.violence.rejectThreshold,
            signals.gore to properties.gore.rejectThreshold,
            signals.hateOrExtremism to properties.hate.rejectThreshold
        ).any { (score, threshold) -> score >= threshold }

    private fun needsReview(signals: ProfilePhotoModerationSignals): Boolean =
        listOf(
            signals.sexualExplicit to properties.sexualExplicit.reviewThreshold,
            signals.sexualSuggestive to properties.sexualSuggestive.reviewThreshold,
            signals.violenceOrThreat to properties.violence.reviewThreshold,
            signals.gore to properties.gore.reviewThreshold,
            signals.hateOrExtremism to properties.hate.reviewThreshold
        ).any { (score, threshold) -> score >= threshold }
}
