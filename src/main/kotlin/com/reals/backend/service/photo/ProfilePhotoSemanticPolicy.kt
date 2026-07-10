package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhotoValidationResult
import org.springframework.stereotype.Component

@Component
class ProfilePhotoSemanticPolicy {
    fun evaluate(signals: ProfilePhotoAnalysisSignals): ProfilePhotoValidationResult {
        return ProfilePhotoValidationResult(
            isPersonPhoto = signals.realFaceCount > 0,
            isFullBody = false,
            status = PhotoValidationStatus.VALIDATED
        )
    }
}
