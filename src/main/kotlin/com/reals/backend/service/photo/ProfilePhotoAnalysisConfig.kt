package com.reals.backend.service.photo

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@ConfigurationProperties(prefix = "profile.photos.sightengine")
data class SightenginePhotoAnalysisProperties(
    val endpoint: String = "https://api.sightengine.com/1.0/check.json",
    val apiUser: String = "",
    val apiSecret: String = "",
    val connectTimeoutMs: Long = 3_000,
    val readTimeoutMs: Long = 10_000
) {
    init {
        require(connectTimeoutMs > 0) {
            "profile.photos.sightengine.connect-timeout-ms must be positive"
        }
        require(readTimeoutMs > 0) {
            "profile.photos.sightengine.read-timeout-ms must be positive"
        }
    }

    fun requireCredentials() {
        require(apiUser.isNotBlank()) {
            "profile.photos.sightengine.api-user must be configured when provider=sightengine"
        }
        require(apiSecret.isNotBlank()) {
            "profile.photos.sightengine.api-secret must be configured when provider=sightengine"
        }
    }
}

@ConfigurationProperties(prefix = "profile.photos.moderation.policy")
data class ProfilePhotoModerationPolicyProperties(
    val sexualExplicit: ReviewRejectScoreThresholds = ReviewRejectScoreThresholds(
        reviewThreshold = 0.50,
        rejectThreshold = 0.80
    ),
    val sexualSuggestive: ReviewScoreThreshold = ReviewScoreThreshold(
        reviewThreshold = 0.80
    ),
    val violence: ReviewRejectScoreThresholds = ReviewRejectScoreThresholds(
        reviewThreshold = 0.50,
        rejectThreshold = 0.85
    ),
    val gore: ReviewRejectScoreThresholds = ReviewRejectScoreThresholds(
        reviewThreshold = 0.40,
        rejectThreshold = 0.80
    ),
    val hate: ReviewRejectScoreThresholds = ReviewRejectScoreThresholds(
        reviewThreshold = 0.50,
        rejectThreshold = 0.85
    )
) {
    init {
        sexualExplicit.validate("sexual-explicit")
        sexualSuggestive.validate("sexual-suggestive")
        violence.validate("violence")
        gore.validate("gore")
        hate.validate("hate")
    }
}

data class ReviewRejectScoreThresholds(
    val reviewThreshold: Double,
    val rejectThreshold: Double
) {
    fun validate(category: String) {
        require(reviewThreshold in 0.0..1.0) {
            "profile.photos.moderation.policy.$category.review-threshold must be between 0.0 and 1.0"
        }
        require(rejectThreshold in 0.0..1.0) {
            "profile.photos.moderation.policy.$category.reject-threshold must be between 0.0 and 1.0"
        }
        require(rejectThreshold >= reviewThreshold) {
            "profile.photos.moderation.policy.$category.reject-threshold must not be weaker than review-threshold"
        }
    }
}

data class ReviewScoreThreshold(
    val reviewThreshold: Double
) {
    fun validate(category: String) {
        require(reviewThreshold in 0.0..1.0) {
            "profile.photos.moderation.policy.$category.review-threshold must be between 0.0 and 1.0"
        }
    }
}

@Configuration
@EnableConfigurationProperties(
    SightenginePhotoAnalysisProperties::class,
    ProfilePhotoModerationPolicyProperties::class
)
class ProfilePhotoAnalysisConfig {

    @Bean
    @Conditional(ProductionSightenginePhotoAnalysisCondition::class)
    fun sightengineRestClient(properties: SightenginePhotoAnalysisProperties): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs))
            setReadTimeout(Duration.ofMillis(properties.readTimeoutMs))
        }

        return RestClient.builder()
            .requestFactory(requestFactory)
            .build()
    }
}

class ProductionSightenginePhotoAnalysisCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        profilePhotoModerationProvider(context.environment) == SIGHTENGINE_PROVIDER &&
            isProductionExecutionProfile(context.environment)
}

class NoopProfilePhotoAnalysisCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val provider = profilePhotoModerationProvider(context.environment)
        return provider == NOOP_PROVIDER ||
            (provider == SIGHTENGINE_PROVIDER && !isProductionExecutionProfile(context.environment))
    }
}

private fun profilePhotoModerationProvider(environment: Environment): String =
    environment.getProperty(PROFILE_PHOTO_MODERATION_PROVIDER_PROPERTY, NOOP_PROVIDER)
        .trim()
        .lowercase()

private fun isProductionExecutionProfile(environment: Environment): Boolean {
    val activeExecutionProfiles = environment.activeProfiles
        .toSet()
        .intersect(EnvironmentExposurePolicy.EXECUTION_PROFILES)
    return activeExecutionProfiles == setOf(EnvironmentExposurePolicy.PROD_PROFILE)
}

private const val PROFILE_PHOTO_MODERATION_PROVIDER_PROPERTY = "profile.photos.moderation.provider"
private const val NOOP_PROVIDER = "none"
private const val SIGHTENGINE_PROVIDER = "sightengine"
