package com.reals.backend.config

import com.google.cloud.vision.v1.ImageAnnotatorClient
import com.reals.backend.service.photo.GoogleVisionPhotoAnalysisProvider
import com.reals.backend.service.photo.GoogleVisionPhotoAnalysisProperties
import com.reals.backend.service.photo.NoopProfilePhotoAnalysisProvider
import com.reals.backend.service.photo.PhotoContentLikelihood
import com.reals.backend.service.photo.ProfilePhotoAnalysisConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ProfilePhotoAnalysisConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            ProfilePhotoAnalysisConfig::class.java,
            NoopProfilePhotoAnalysisProvider::class.java
        )

    @Test
    fun `default face threshold is zero point five`() {
        contextRunner.run { context ->
            val properties = context.getBean(GoogleVisionPhotoAnalysisProperties::class.java)

            assertEquals(0.50, properties.faceDetectionConfidenceThreshold)
        }
    }

    @Test
    fun `invalid face threshold fails startup`() {
        contextRunner
            .withPropertyValues("profile.photos.google-vision.face-detection-confidence-threshold=1.01")
            .run { context ->
                assertThat(context.startupFailure).isNotNull()
            }
    }

    @Test
    fun `SafeSearch default thresholds are bound`() {
        contextRunner.run { context ->
            val safeSearch = context.getBean(GoogleVisionPhotoAnalysisProperties::class.java).safeSearch

            assertEquals(PhotoContentLikelihood.POSSIBLE, safeSearch.adult.reviewThreshold)
            assertEquals(PhotoContentLikelihood.LIKELY, safeSearch.adult.rejectThreshold)
            assertEquals(PhotoContentLikelihood.POSSIBLE, safeSearch.violence.reviewThreshold)
            assertEquals(PhotoContentLikelihood.LIKELY, safeSearch.violence.rejectThreshold)
            assertEquals(PhotoContentLikelihood.POSSIBLE, safeSearch.racy.reviewThreshold)
            assertEquals(PhotoContentLikelihood.VERY_LIKELY, safeSearch.racy.rejectThreshold)
            assertEquals(PhotoContentLikelihood.LIKELY, safeSearch.medical.reviewThreshold)
            assertEquals(PhotoContentLikelihood.LIKELY, safeSearch.spoof.reviewThreshold)
        }
    }

    @Test
    fun `invalid reject threshold weaker than review threshold fails startup`() {
        contextRunner
            .withPropertyValues(
                "profile.photos.google-vision.safe-search.racy.review-threshold=LIKELY",
                "profile.photos.google-vision.safe-search.racy.reject-threshold=POSSIBLE"
            )
            .run { context ->
                assertThat(context.startupFailure).isNotNull()
            }
    }

    @Test
    fun `provider none remains default and does not create Google Vision client`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(NoopProfilePhotoAnalysisProvider::class.java)
            assertThat(context).doesNotHaveBean(ImageAnnotatorClient::class.java)
        }
    }

    @Test
    fun `Google Vision client and provider beans are conditional on google vision provider`() {
        val clientCondition = ProfilePhotoAnalysisConfig::class.java
            .getDeclaredMethod("imageAnnotatorClient")
            .getAnnotation(ConditionalOnProperty::class.java)
        val providerCondition = GoogleVisionPhotoAnalysisProvider::class.java
            .getAnnotation(ConditionalOnProperty::class.java)

        assertEquals("profile.photos.moderation", clientCondition.prefix)
        assertEquals("google-vision", clientCondition.havingValue)
        assertEquals(listOf("provider"), clientCondition.name.toList())
        assertEquals("profile.photos.moderation", providerCondition.prefix)
        assertEquals("google-vision", providerCondition.havingValue)
        assertEquals(listOf("provider"), providerCondition.name.toList())
    }
}
