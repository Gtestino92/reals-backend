package com.reals.backend.service.photo

import com.google.cloud.vision.v1.ImageAnnotatorClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "profile.photos.google-vision")
data class GoogleVisionPhotoAnalysisProperties(
    val faceDetectionConfidenceThreshold: Double = 0.50,
    val safeSearch: GoogleVisionSafeSearchProperties = GoogleVisionSafeSearchProperties()
) {
    init {
        require(faceDetectionConfidenceThreshold in 0.0..1.0) {
            "profile.photos.google-vision.face-detection-confidence-threshold must be between 0.0 and 1.0"
        }
    }
}

data class GoogleVisionSafeSearchProperties(
    val adult: ReviewRejectThresholds = ReviewRejectThresholds(
        reviewThreshold = PhotoContentLikelihood.POSSIBLE,
        rejectThreshold = PhotoContentLikelihood.LIKELY
    ),
    val violence: ReviewRejectThresholds = ReviewRejectThresholds(
        reviewThreshold = PhotoContentLikelihood.POSSIBLE,
        rejectThreshold = PhotoContentLikelihood.LIKELY
    ),
    val racy: ReviewRejectThresholds = ReviewRejectThresholds(
        reviewThreshold = PhotoContentLikelihood.POSSIBLE,
        rejectThreshold = PhotoContentLikelihood.VERY_LIKELY
    ),
    val medical: ReviewThreshold = ReviewThreshold(
        reviewThreshold = PhotoContentLikelihood.LIKELY
    ),
    val spoof: ReviewThreshold = ReviewThreshold(
        reviewThreshold = PhotoContentLikelihood.LIKELY
    )
) {
    init {
        adult.validate("adult")
        violence.validate("violence")
        racy.validate("racy")
        medical.validate("medical")
        spoof.validate("spoof")
    }
}

data class ReviewRejectThresholds(
    val reviewThreshold: PhotoContentLikelihood,
    val rejectThreshold: PhotoContentLikelihood
) {
    fun validate(category: String) {
        require(reviewThreshold != PhotoContentLikelihood.UNKNOWN) {
            "profile.photos.google-vision.safe-search.$category.review-threshold cannot be UNKNOWN"
        }
        require(rejectThreshold != PhotoContentLikelihood.UNKNOWN) {
            "profile.photos.google-vision.safe-search.$category.reject-threshold cannot be UNKNOWN"
        }
        require(rejectThreshold.isMoreRestrictiveOrEqualTo(reviewThreshold)) {
            "profile.photos.google-vision.safe-search.$category.reject-threshold must not be less strict than review-threshold"
        }
    }
}

data class ReviewThreshold(
    val reviewThreshold: PhotoContentLikelihood
) {
    fun validate(category: String) {
        require(reviewThreshold != PhotoContentLikelihood.UNKNOWN) {
            "profile.photos.google-vision.safe-search.$category.review-threshold cannot be UNKNOWN"
        }
    }
}

@Configuration
@EnableConfigurationProperties(GoogleVisionPhotoAnalysisProperties::class)
class ProfilePhotoAnalysisConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
        prefix = "profile.photos.moderation",
        name = ["provider"],
        havingValue = "google-vision"
    )
    fun imageAnnotatorClient(): ImageAnnotatorClient =
        ImageAnnotatorClient.create()
}
