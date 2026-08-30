package com.reals.backend.config.environment

import com.reals.backend.config.MatchmakingRankingProperties
import com.reals.backend.config.s3.S3StorageProperties
import com.reals.backend.config.s3.resolved
import com.reals.backend.config.security.appcheck.FirebaseAppCheckProperties
import com.reals.backend.config.security.ratelimit.RateLimitProperties
import com.reals.backend.service.photo.ProfilePhotoRuntimeProperties
import com.reals.backend.service.photo.SIGHTENGINE_PROVIDER
import com.reals.backend.service.photo.SightenginePhotoAnalysisProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class ProductionRuntimeGuardrails(
    private val environmentExposurePolicy: EnvironmentExposurePolicy,
    private val profilePhotoProperties: ProfilePhotoRuntimeProperties,
    private val sightengineProperties: SightenginePhotoAnalysisProperties,
    private val rateLimitProperties: RateLimitProperties
) : InitializingBean {

    override fun afterPropertiesSet() {
        if (!environmentExposurePolicy.isProduction()) {
            return
        }

        validateProductionPhotoAnalysisProvider()
        validateProductionPhotoActivationModeration()
        validateProductionRateLimiting()
    }

    private fun validateProductionPhotoAnalysisProvider() {
        val provider = profilePhotoProperties.normalizedModerationProvider()
        require(provider == SIGHTENGINE_PROVIDER) {
            "profile.photos.moderation.provider must be sightengine in prod"
        }

        sightengineProperties.requireValidProductionEndpoint()
    }

    private fun validateProductionPhotoActivationModeration() {
        require(profilePhotoProperties.requireModerationApprovalForActivation) {
            "profile.photos.require-moderation-approval-for-activation must be true in prod"
        }
    }

    private fun validateProductionRateLimiting() {
        require(rateLimitProperties.enabled) {
            "security.rate-limit.enabled must be true in prod"
        }
    }
}

@Component
class ProductionRuntimeConfigurationSummaryLogger(
    private val environmentExposurePolicy: EnvironmentExposurePolicy,
    private val profilePhotoProperties: ProfilePhotoRuntimeProperties,
    private val appCheckProperties: FirebaseAppCheckProperties,
    private val rateLimitProperties: RateLimitProperties,
    private val storageProperties: S3StorageProperties,
    private val matchmakingRankingProperties: MatchmakingRankingProperties
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!environmentExposurePolicy.isProduction()) {
            return
        }

        val resolvedStorage = storageProperties.resolved()
        log.info(
            "Production runtime configuration: executionProfile=prod " +
                "photoModerationProvider={} moderationApprovalRequiredForActivation={} " +
                "appCheckMode={} rateLimitEnabled={} storageCredentialsMode={} " +
                "storageReadUrlMode={} matchmakingRankingMode={} matchmakingAffinityMode={}",
            profilePhotoProperties.normalizedModerationProvider(),
            profilePhotoProperties.requireModerationApprovalForActivation,
            appCheckProperties.mode,
            rateLimitProperties.enabled,
            resolvedStorage.credentialsMode,
            resolvedStorage.readUrlMode,
            matchmakingRankingProperties.mode,
            matchmakingRankingProperties.affinity.mode
        )
    }
}
