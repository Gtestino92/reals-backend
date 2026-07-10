package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhotoValidationResult
import org.springframework.stereotype.Component

@Component
class ProfilePhotoSemanticPolicy(
    private val properties: GoogleVisionPhotoAnalysisProperties
) {
    fun evaluate(signals: ProfilePhotoAnalysisSignals): ProfilePhotoValidationResult {
        val hasQualifyingFace = signals.faceDetectionConfidences.any {
            it >= properties.faceDetectionConfidenceThreshold
        }

        return ProfilePhotoValidationResult(
            isPersonPhoto = hasQualifyingFace,
            isFullBody = false,
            status = PhotoValidationStatus.VALIDATED
        )
    }
}
