package com.reals.backend.service.photo

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
class NoopPhotoModerationProvider : PhotoModerationProvider {
    override fun moderate(request: PhotoModerationRequest): PhotoModerationResult =
        PhotoModerationResult(
            status = PhotoModerationStatus.APPROVED,
            provider = "none"
        )
}
