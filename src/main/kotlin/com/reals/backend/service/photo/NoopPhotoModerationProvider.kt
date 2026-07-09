package com.reals.backend.service.photo

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.PhotoModerationStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "profile.photos.moderation",
    name = ["provider"],
    havingValue = "none",
    matchIfMissing = true
)
class NoopPhotoModerationProvider(
    private val environmentExposurePolicy: EnvironmentExposurePolicy
) : PhotoModerationProvider {
    override fun moderate(request: PhotoModerationRequest): PhotoModerationResult {
        if (environmentExposurePolicy.isProduction()) {
            return PhotoModerationResult(
                status = PhotoModerationStatus.NEEDS_REVIEW,
                provider = "none",
                reason = "Photo moderation provider is not configured"
            )
        }

        return PhotoModerationResult(
            status = PhotoModerationStatus.APPROVED,
            provider = "none"
        )
    }
}
