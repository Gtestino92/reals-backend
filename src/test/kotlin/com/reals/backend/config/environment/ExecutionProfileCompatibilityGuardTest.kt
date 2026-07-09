package com.reals.backend.config.environment

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecutionProfileCompatibilityGuardTest {

    @Test
    fun `single execution profiles are allowed`() {
        listOf(
            "local-nodb",
            "local-postgres",
            "local-firebase",
            "dev",
            "prod",
            "test"
        ).forEach { profile ->
            guardFor(profile).afterSingletonsInstantiated()
        }
    }

    @Test
    fun `auxiliary profiles do not create false execution profile conflicts`() {
        guardFor("prod", "some-observability-profile").afterSingletonsInstantiated()
    }

    @Test
    fun `missing execution profile fails startup`() {
        assertFailsWith<IllegalStateException> {
            guardFor("some-observability-profile").afterSingletonsInstantiated()
        }
    }

    @Test
    fun `incompatible execution profile combinations fail startup`() {
        listOf(
            arrayOf("prod", "local-firebase"),
            arrayOf("dev", "local-postgres"),
            arrayOf("dev", "prod"),
            arrayOf("local-nodb", "local-firebase"),
            arrayOf("test", "prod")
        ).forEach { profiles ->
            val ex = assertFailsWith<IllegalStateException> {
                guardFor(*profiles).afterSingletonsInstantiated()
            }

            profiles.forEach { profile ->
                assertTrue(ex.message.orEmpty().contains(profile))
            }
        }
    }

    private fun guardFor(vararg profiles: String): ExecutionProfileCompatibilityGuard =
        ExecutionProfileCompatibilityGuard(
            EnvironmentExposurePolicy.forActiveProfiles(*profiles)
        )
}
