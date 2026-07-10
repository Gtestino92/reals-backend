package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoModerationStatus
import org.springframework.stereotype.Component

@Component
class VisionPhotoModerationPolicy(
    private val properties: GoogleVisionPhotoAnalysisProperties
) {
    fun evaluate(signals: ProfilePhotoAnalysisSignals): PhotoModerationResult {
        val safeSearch = signals.safeSearch

        if (rejects(safeSearch)) {
            return PhotoModerationResult(
                status = PhotoModerationStatus.REJECTED,
                provider = signals.provider,
                reason = "safe-search-rejected"
            )
        }

        if (containsUnknown(safeSearch) || needsReview(safeSearch)) {
            return PhotoModerationResult(
                status = PhotoModerationStatus.NEEDS_REVIEW,
                provider = signals.provider,
                reason = "safe-search-needs-review"
            )
        }

        return PhotoModerationResult(
            status = PhotoModerationStatus.APPROVED,
            provider = signals.provider
        )
    }

    private fun rejects(safeSearch: PhotoSafeSearchSignals): Boolean {
        val thresholds = properties.safeSearch
        return listOf(
            safeSearch.adult to thresholds.adult.rejectThreshold,
            safeSearch.violence to thresholds.violence.rejectThreshold,
            safeSearch.racy to thresholds.racy.rejectThreshold
        ).any { (signal, threshold) -> signal.isKnownAtLeast(threshold) }
    }

    private fun needsReview(safeSearch: PhotoSafeSearchSignals): Boolean {
        val thresholds = properties.safeSearch
        return listOf(
            safeSearch.adult to thresholds.adult.reviewThreshold,
            safeSearch.violence to thresholds.violence.reviewThreshold,
            safeSearch.racy to thresholds.racy.reviewThreshold,
            safeSearch.medical to thresholds.medical.reviewThreshold,
            safeSearch.spoof to thresholds.spoof.reviewThreshold
        ).any { (signal, threshold) -> signal.isKnownAtLeast(threshold) }
    }

    private fun containsUnknown(safeSearch: PhotoSafeSearchSignals): Boolean =
        listOf(
            safeSearch.adult,
            safeSearch.violence,
            safeSearch.racy,
            safeSearch.medical,
            safeSearch.spoof
        ).any { it == PhotoContentLikelihood.UNKNOWN }
}
