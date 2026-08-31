package com.reals.backend.config

import com.reals.backend.service.identity.FirebaseAuthRestConfig
import com.reals.backend.service.photo.NoopProfilePhotoAnalysisProvider
import com.reals.backend.service.photo.NoopProfilePhotoAnalysisCondition
import com.reals.backend.service.photo.ProfilePhotoAnalysisConfig
import com.reals.backend.service.photo.ProfilePhotoModerationPolicyProperties
import com.reals.backend.service.photo.SightengineProfilePhotoAnalysisCondition
import com.reals.backend.service.photo.SightenginePhotoAnalysisProvider
import com.reals.backend.service.photo.SightenginePhotoAnalysisProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Conditional
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

    private val contextRunnerWithFirebaseAuthRest = contextRunner
        .withUserConfiguration(FirebaseAuthRestConfig::class.java)

    @Test
    fun `provider none remains default and requires no Sightengine credentials`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(NoopProfilePhotoAnalysisProvider::class.java)
            assertThat(context).doesNotHaveBean(SightenginePhotoAnalysisProvider::class.java)
            assertThat(context).doesNotHaveBean(RestClient::class.java)
        }
    }

    @Test
    fun `local default selects noop and requires no Sightengine credentials`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("local-postgres") }
            .run { context ->
                assertThat(context).hasSingleBean(NoopProfilePhotoAnalysisProvider::class.java)
                assertThat(context).doesNotHaveBean(SightenginePhotoAnalysisProvider::class.java)
                assertThat(context).doesNotHaveBean(RestClient::class.java)
            }
    }

    @Test
    fun `dev default selects noop and requires no Sightengine credentials`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("dev") }
            .run { context ->
                assertThat(context).hasSingleBean(NoopProfilePhotoAnalysisProvider::class.java)
                assertThat(context).doesNotHaveBean(SightenginePhotoAnalysisProvider::class.java)
                assertThat(context).doesNotHaveBean(RestClient::class.java)
            }
    }

    @Test
    fun `dev Sightengine opt-in selects Sightengine provider`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("dev") }
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
    fun `dev Sightengine opt-in uses Sightengine RestClient when Firebase Auth RestClient also exists`() {
        contextRunnerWithFirebaseAuthRest
            .withInitializer { context -> context.environment.setActiveProfiles("dev") }
            .withPropertyValues(
                "profile.photos.moderation.provider=sightengine",
                "profile.photos.sightengine.api-user=test-user",
                "profile.photos.sightengine.api-secret=test-secret"
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasBean("firebaseAuthRestClient")
                assertThat(context).hasBean("sightengineRestClient")
                val provider = context.getBean(SightenginePhotoAnalysisProvider::class.java)
                val restClientField = SightenginePhotoAnalysisProvider::class.java
                    .getDeclaredField("restClient")
                    .apply { isAccessible = true }

                assertThat(restClientField.get(provider))
                    .isSameAs(context.getBean("sightengineRestClient"))
                    .isNotSameAs(context.getBean("firebaseAuthRestClient"))
            }
    }

    @Test
    fun `prod Sightengine uses Sightengine RestClient when Firebase Auth RestClient also exists`() {
        contextRunnerWithFirebaseAuthRest
            .withInitializer { context -> context.environment.setActiveProfiles("prod") }
            .withPropertyValues(
                "profile.photos.moderation.provider=sightengine",
                "profile.photos.sightengine.api-user=test-user",
                "profile.photos.sightengine.api-secret=test-secret"
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasBean("firebaseAuthRestClient")
                assertThat(context).hasBean("sightengineRestClient")
                val provider = context.getBean(SightenginePhotoAnalysisProvider::class.java)
                val restClientField = SightenginePhotoAnalysisProvider::class.java
                    .getDeclaredField("restClient")
                    .apply { isAccessible = true }

                assertThat(restClientField.get(provider))
                    .isSameAs(context.getBean("sightengineRestClient"))
                    .isNotSameAs(context.getBean("firebaseAuthRestClient"))
            }
    }

    @Test
    fun `prod Sightengine selects Sightengine provider`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("prod") }
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
    fun `local explicit Sightengine fails instead of silently falling back to noop`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("local-postgres") }
            .withPropertyValues(
                "profile.photos.moderation.provider=sightengine",
                "profile.photos.sightengine.api-user=test-user",
                "profile.photos.sightengine.api-secret=test-secret"
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("provider=sightengine is supported only in dev or prod")
            }
    }

    @Test
    fun `dev selecting Sightengine without api user fails startup`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("dev") }
            .withPropertyValues(
                "profile.photos.moderation.provider=sightengine",
                "profile.photos.sightengine.api-secret=test-secret"
            )
            .run { context ->
                assertThat(context.startupFailure).isNotNull()
            }
    }

    @Test
    fun `dev selecting Sightengine without api secret fails startup`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("dev") }
            .withPropertyValues(
                "profile.photos.moderation.provider=sightengine",
                "profile.photos.sightengine.api-user=test-user"
            )
            .run { context ->
                assertThat(context.startupFailure).isNotNull()
            }
    }

    @Test
    fun `dev selecting Sightengine with invalid endpoint fails startup`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("dev") }
            .withPropertyValues(
                "profile.photos.moderation.provider=sightengine",
                "profile.photos.sightengine.api-user=test-user",
                "profile.photos.sightengine.api-secret=test-secret",
                "profile.photos.sightengine.endpoint=http://api.sightengine.com/1.0/check.json"
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("valid absolute HTTPS URI when provider=sightengine")
            }
    }

    @Test
    fun `prod selecting Sightengine without credentials fails startup`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("prod") }
            .withPropertyValues("profile.photos.moderation.provider=sightengine")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining("api-user")
            }
    }

    @Test
    fun `prod explicit noop fails without selecting Sightengine`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("prod") }
            .withPropertyValues("profile.photos.moderation.provider=none")
            .run { context ->
                assertThat(context).hasSingleBean(NoopProfilePhotoAnalysisProvider::class.java)
                assertThat(context).doesNotHaveBean(SightenginePhotoAnalysisProvider::class.java)
            }
    }

    @Test
    fun `unknown provider fails startup`() {
        contextRunner
            .withPropertyValues("profile.photos.moderation.provider=unknown")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("profile.photos.moderation.provider must be one of")
            }
    }

    @Test
    fun `default moderation policy thresholds are bound`() {
        contextRunner.run { context ->
            val properties = context.getBean(ProfilePhotoModerationPolicyProperties::class.java)

            assertEquals(0.50, properties.sexualExplicit.reviewThreshold)
            assertEquals(0.80, properties.sexualExplicit.rejectThreshold)
            assertEquals(0.50, properties.sexualSuggestive.reviewThreshold)
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
    fun `photo analysis provider conditional metadata uses execution-profile-aware selectors`() {
        val providerCondition = SightenginePhotoAnalysisProvider::class.java
            .getAnnotation(Conditional::class.java)
        val noopCondition = NoopProfilePhotoAnalysisProvider::class.java
            .getAnnotation(Conditional::class.java)

        assertEquals(
            listOf(SightengineProfilePhotoAnalysisCondition::class),
            providerCondition.value.toList()
        )
        assertEquals(
            listOf(NoopProfilePhotoAnalysisCondition::class),
            noopCondition.value.toList()
        )
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
