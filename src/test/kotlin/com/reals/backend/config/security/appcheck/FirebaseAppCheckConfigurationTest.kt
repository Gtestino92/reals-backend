package com.reals.backend.config.security.appcheck

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class FirebaseAppCheckConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            FirebaseAppCheckConfig::class.java,
            EnvironmentExposurePolicy::class.java
        )

    @Test
    fun `ordinary non firebase profiles do not create app check filter`() {
        listOf("test", "local-nodb", "local-postgres").forEach { profile ->
            contextRunner
                .withPropertyValues("spring.profiles.active=$profile")
                .run { context ->
                    assertThat(context).doesNotHaveBean(FirebaseAppCheckFilter::class.java)
                    assertThat(context).doesNotHaveBean(FirebaseAppCheckVerifier::class.java)
                }
        }
    }

    @Test
    fun `local firebase defaults to disabled`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=local-firebase")
            .run { context ->
                assertThat(context).hasSingleBean(FirebaseAppCheckFilter::class.java)
                assertThat(context.getBean(FirebaseAppCheckProperties::class.java).mode)
                    .isEqualTo(FirebaseAppCheckMode.DISABLED)
            }
    }

    @Test
    fun `dev defaults to disabled`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=dev")
            .run { context ->
                assertThat(context).hasSingleBean(FirebaseAppCheckFilter::class.java)
                assertThat(context).hasSingleBean(FirebaseAppCheckStartupValidator::class.java)
                assertThat(context.getBean(FirebaseAppCheckProperties::class.java).mode)
                    .isEqualTo(FirebaseAppCheckMode.DISABLED)
            }
    }

    @Test
    fun `dev rejects enabled app check without provider configuration`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=dev",
                "security.app-check.mode=ENFORCED"
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("project-number")
            }
    }

    @Test
    fun `dev accepts enabled app check with provider configuration`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=dev",
                "security.app-check.mode=ENFORCED",
                "security.app-check.project-number=123456789",
                "security.app-check.allowed-app-ids[0]=1:123456789:android:app",
                "security.app-check.jwks-uri=https://firebaseappcheck.googleapis.com/v1/jwks"
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(FirebaseAppCheckFilter::class.java)
                assertThat(context).hasSingleBean(FirebaseAppCheckStartupValidator::class.java)
            }
    }

    @Test
    fun `prod rejects missing app check configuration`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "security.app-check.mode=ENFORCED"
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("project-number")
            }
    }

    @Test
    fun `prod rejects modes other than enforced`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "security.app-check.mode=MONITOR",
                "security.app-check.project-number=123456789",
                "security.app-check.allowed-app-ids[0]=1:123456789:android:app",
                "security.app-check.jwks-uri=https://firebaseappcheck.googleapis.com/v1/jwks"
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("ENFORCED")
            }
    }

    @Test
    fun `prod accepts valid enforced configuration`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "security.app-check.mode=ENFORCED",
                "security.app-check.project-number=123456789",
                "security.app-check.allowed-app-ids[0]=1:123456789:android:app",
                "security.app-check.jwks-uri=https://firebaseappcheck.googleapis.com/v1/jwks"
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(FirebaseAppCheckFilter::class.java)
                assertThat(context).hasSingleBean(FirebaseAppCheckStartupValidator::class.java)
            }
    }
}
