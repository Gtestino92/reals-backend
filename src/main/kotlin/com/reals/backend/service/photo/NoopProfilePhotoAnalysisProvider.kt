package com.reals.backend.service.photo

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "profile.photos.moderation",
    name = ["provider"],
    havingValue = "none",
    matchIfMissing = true
)
class NoopProfilePhotoAnalysisProvider : ProfilePhotoAnalysisProvider {
    override fun analyze(request: ProfilePhotoAnalysisRequest): ProfilePhotoAnalysisProviderResult =
        ProfilePhotoAnalysisProviderResult.NotConfigured(provider = "none")
}
