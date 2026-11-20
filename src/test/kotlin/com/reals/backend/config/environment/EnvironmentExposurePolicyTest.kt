package com.reals.backend.config.environment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

class EnvironmentExposurePolicyTest {

    @Test
    fun `prod is production`() {
        assertEquals(
            true,
            EnvironmentExposurePolicy.forActiveProfiles("prod").isProduction()
        )
    }

    @Test
    fun `non-prod execution profiles are not production`() {
        listOf(
            "local-nodb",
            "local-postgres",
            "local-firebase",
            "dev",
            "test"
        ).forEach { profile ->
            assertEquals(
                false,
                EnvironmentExposurePolicy.forActiveProfiles(profile).isProduction(),
                "$profile should not be production"
            )
        }
    }

    @Test
    fun `local execution profiles allow public local-dev tooling only`() {
        listOf(
            "local-nodb",
            "local-postgres",
            "local-firebase"
        ).forEach { profile ->
            val policy = EnvironmentExposurePolicy.forActiveProfiles(profile)

            assertEquals(true, policy.localDevEndpointsAllowed(), "$profile should allow local-dev tooling")
            assertEquals(false, policy.devAdminToolingAllowed(), "$profile should not require dev admin tooling")
        }
    }

    @Test
    fun `exact dev requires explicit enablement for admin tooling`() {
        val policy = EnvironmentExposurePolicy.forActiveProfiles("dev")

        assertEquals(false, policy.localDevEndpointsAllowed())
        assertEquals(false, policy.devAdminToolingAllowed())
        val environment = MockEnvironment().withProperty("local-dev.remote-tooling-enabled", "true")
        environment.setActiveProfiles("dev")
        assertEquals(true, EnvironmentExposurePolicy(environment).devAdminToolingAllowed())
        environment.setActiveProfiles("dev", "prod")
        assertEquals(false, EnvironmentExposurePolicy(environment).devAdminToolingAllowed())
    }

    @Test
    fun `prod and test deny local and dev tooling`() {
        listOf("prod", "test").forEach { profile ->
            val policy = EnvironmentExposurePolicy.forActiveProfiles(profile)

            assertEquals(false, policy.localDevEndpointsAllowed(), "$profile should deny local tooling")
            assertEquals(false, policy.devAdminToolingAllowed(), "$profile should deny dev admin tooling")
        }
    }

    @Test
    fun `mixed incompatible execution profiles remain rejected`() {
        assertThrows<IllegalStateException> {
            EnvironmentExposurePolicy.forActiveProfiles("dev", "prod").validateCompatibleExecutionProfiles()
        }

        assertThrows<IllegalStateException> {
            EnvironmentExposurePolicy.forActiveProfiles("local-postgres", "dev").validateCompatibleExecutionProfiles()
        }
    }
}
