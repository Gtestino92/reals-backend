package com.reals.backend.config

import com.reals.backend.service.photo.NoopProfilePhotoAnalysisProvider
import com.reals.backend.service.photo.ProfilePhotoAnalysisConfig
import com.reals.backend.service.photo.ProfilePhotoModerationPolicyProperties
import com.reals.backend.service.photo.SightenginePhotoAnalysisProvider
import com.reals.backend.service.photo.SightenginePhotoAnalysisProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path

class ProfilePhotoAnalysisConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            ProfilePhotoAnalysisConfig::class.java,
            NoopProfilePhotoAnalysisProvider::class.java,
            SightenginePhotoAnalysisProvider::class.java
        )

    @Test
    fun `provider none remains default and requires no Sightengine credentials`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(NoopProfilePhotoAnalysisProvider::class.java)
            assertThat(context).doesNotHaveBean(SightenginePhotoAnalysisProvider::class.java)
            assertThat(context).doesNotHaveBean(RestClient::class.java)
        }
    }

    @Test
    fun `Sightengine provider bean is conditional on sightengine provider`() {
        contextRunner
            .withPropertyValues(
                "profile.photos.moderation.provider=sightengine",
                "profile.photos.sightengine.api-user=test-user",
                "profile.photos.sightengine.api-secret=test-secret"
            )
            .run { context ->
                assertThat(context).hasSingleBean(SightenginePhotoAnalysisProvider::class.java)
                assertThat(context).hasSingleBean(RestClient::class.java)
            }
    }

    @Test
    fun `selecting Sightengine without api user fails startup`() {
        contextRunner
            .withPropertyValues(
                "profile.photos.moderation.provider=sightengine",
                "profile.photos.sightengine.api-secret=test-secret"
            )
            .run { context ->
                assertThat(context.startupFailure).isNotNull()
            }
    }

    @Test
    fun `selecting Sightengine without api secret fails startup`() {
        contextRunner
            .withPropertyValues(
                "profile.photos.moderation.provider=sightengine",
                "profile.photos.sightengine.api-user=test-user"
            )
            .run { context ->
                assertThat(context.startupFailure).isNotNull()
            }
    }

    @Test
    fun `default moderation policy thresholds are bound`() {
        contextRunner.run { context ->
            val properties = context.getBean(ProfilePhotoModerationPolicyProperties::class.java)

            assertEquals(0.50, properties.sexualExplicit.reviewThreshold)
            assertEquals(0.80, properties.sexualExplicit.rejectThreshold)
            assertEquals(0.80, properties.sexualSuggestive.reviewThreshold)
            assertEquals(0.50, properties.violence.reviewThreshold)
            assertEquals(0.85, properties.violence.rejectThreshold)
            assertEquals(0.40, properties.gore.reviewThreshold)
            assertEquals(0.80, properties.gore.rejectThreshold)
            assertEquals(0.50, properties.hate.reviewThreshold)
            assertEquals(0.85, properties.hate.rejectThreshold)
        }
    }

    @Test
    fun `invalid score threshold outside range fails startup`() {
        contextRunner
            .withPropertyValues("profile.photos.moderation.policy.gore.review-threshold=1.01")
            .run { context ->
                assertThat(context.startupFailure).isNotNull()
            }
    }

    @Test
    fun `reject threshold weaker than review threshold fails startup`() {
        contextRunner
            .withPropertyValues(
                "profile.photos.moderation.policy.hate.review-threshold=0.90",
                "profile.photos.moderation.policy.hate.reject-threshold=0.80"
            )
            .run { context ->
                assertThat(context.startupFailure).isNotNull()
            }
    }

    @Test
    fun `timeout values must be positive`() {
        contextRunner
            .withPropertyValues("profile.photos.sightengine.connect-timeout-ms=0")
            .run { context ->
                assertThat(context.startupFailure).isNotNull()
            }
    }

    @Test
    fun `Sightengine provider conditional metadata uses existing selector`() {
        val providerCondition = SightenginePhotoAnalysisProvider::class.java
            .getAnnotation(ConditionalOnProperty::class.java)

        assertEquals("profile.photos.moderation", providerCondition.prefix)
        assertEquals("sightengine", providerCondition.havingValue)
        assertEquals(listOf("provider"), providerCondition.name.toList())
    }

    @Test
    fun `Google Vision classes are no longer present`() {
        assertThat(
            Files.exists(
                Path.of("src/main/kotlin/com/reals/backend/service/photo/GoogleVisionPhotoAnalysisProvider.kt")
            )
        ).isFalse()
        assertThat(Files.readString(Path.of("pom.xml")))
            .doesNotContain("google-cloud-vision")
            .doesNotContain("google-cloud-vision.version")
    }
}
