package com.reals.backend.config.security

import com.google.firebase.auth.FirebaseAuth
import com.reals.backend.controller.localdev.LocalFirebaseEmailVerificationController
import com.reals.backend.service.localdev.LocalDevPairHistoryResetService
import com.reals.backend.service.localdev.LocalFirebaseEmailVerificationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import java.util.function.Supplier

class LocalFirebaseEmailVerificationExposureTest {

    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(LocalFirebaseEmailVerificationScanConfig::class.java)
            .withBean(
                FirebaseAuth::class.java,
                Supplier { Mockito.mock(FirebaseAuth::class.java) }
            )

    @Test
    fun `local-firebase profile with property true registers controller and service`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=local-firebase",
                "local-dev.firebase.email-auto-verification-enabled=true"
            )
            .run { context ->
                assertThat(context).hasSingleBean(LocalFirebaseEmailVerificationController::class.java)
                assertThat(context).hasSingleBean(LocalFirebaseEmailVerificationService::class.java)
            }
    }

    @Test
    fun `local-firebase profile with property false does not register endpoint beans`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=local-firebase",
                "local-dev.firebase.email-auto-verification-enabled=false"
            )
            .run { context ->
                assertThat(context).doesNotHaveBean(LocalFirebaseEmailVerificationController::class.java)
                assertThat(context).doesNotHaveBean(LocalFirebaseEmailVerificationService::class.java)
            }
    }

    @Test
    fun `dev profile with property true does not register endpoint beans`() {
        assertEndpointBeansAbsentFor("dev")
    }

    @Test
    fun `prod profile with property true does not register endpoint beans`() {
        assertEndpointBeansAbsentFor("prod")
    }

    @Test
    fun `local-nodb profile with property true does not register endpoint beans`() {
        assertEndpointBeansAbsentFor("local-nodb")
    }

    @Test
    fun `local-postgres profile with property true does not register endpoint beans`() {
        assertEndpointBeansAbsentFor("local-postgres")
    }

    @Test
    fun `test profile without explicit property does not register endpoint beans`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run { context ->
                assertThat(context).doesNotHaveBean(LocalFirebaseEmailVerificationController::class.java)
                assertThat(context).doesNotHaveBean(LocalFirebaseEmailVerificationService::class.java)
            }
    }

    private fun assertEndpointBeansAbsentFor(profile: String) {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=$profile",
                "local-dev.firebase.email-auto-verification-enabled=true"
            )
            .run { context ->
                assertThat(context).doesNotHaveBean(LocalFirebaseEmailVerificationController::class.java)
                assertThat(context).doesNotHaveBean(LocalFirebaseEmailVerificationService::class.java)
            }
    }

    @Configuration
    @ComponentScan(
        basePackageClasses = [
            LocalFirebaseEmailVerificationController::class,
            LocalFirebaseEmailVerificationService::class
        ],
        excludeFilters = [
            ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = [LocalDevPairHistoryResetService::class]
            )
        ]
    )
    class LocalFirebaseEmailVerificationScanConfig
}
