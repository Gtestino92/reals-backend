package com.reals.backend.config.environment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
