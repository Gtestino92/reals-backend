package com.reals.backend.service.photo

import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component

@Component
@Conditional(NoopProfilePhotoAnalysisCondition::class)
class NoopProfilePhotoAnalysisProvider : ProfilePhotoAnalysisProvider {
    override fun analyze(request: ProfilePhotoAnalysisRequest): ProfilePhotoAnalysisProviderResult =
        ProfilePhotoAnalysisProviderResult.NotConfigured(provider = NOOP_PROVIDER)
}
