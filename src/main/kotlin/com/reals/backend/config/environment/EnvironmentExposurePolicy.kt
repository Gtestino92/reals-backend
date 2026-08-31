package com.reals.backend.config.environment

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class EnvironmentExposurePolicy private constructor(
    private val activeProfiles: Set<String>
) {

    @Autowired
    constructor(environment: Environment) : this(environment.activeProfiles.toSet())

    fun activeExecutionProfiles(): Set<String> =
        activeProfiles.intersect(EXECUTION_PROFILES)

    fun validateCompatibleExecutionProfiles() {
        val activeExecutionProfiles = activeExecutionProfiles()

        if (activeExecutionProfiles.isEmpty()) {
            throw IllegalStateException(
                "No active execution profile configured. Exactly one of " +
                    EXECUTION_PROFILES.sorted().joinToString(", ") +
                    " must be active."
            )
        }

        if (activeExecutionProfiles.size > 1) {
            throw IllegalStateException(
                "Incompatible active execution profiles: " +
                    activeExecutionProfiles.sorted().joinToString(", ") +
                    ". Exactly one of " +
                    EXECUTION_PROFILES.sorted().joinToString(", ") +
                    " may be active."
            )
        }
    }

    fun localDevEndpointsAllowed(): Boolean =
        activeExecutionProfiles().any { it in LOCAL_EXECUTION_PROFILES }

    fun devAdminToolingAllowed(): Boolean =
        activeExecutionProfiles() == setOf(DEV_PROFILE)

    fun h2ConsoleAllowed(): Boolean =
        activeExecutionProfiles() == setOf(LOCAL_NODB_PROFILE)

    fun isProduction(): Boolean =
        activeExecutionProfiles() == setOf(PROD_PROFILE)

    companion object {
        const val LOCAL_NODB_PROFILE = "local-nodb"
        const val LOCAL_FIREBASE_PROFILE = "local-firebase"
        const val DEV_PROFILE = "dev"
        const val PROD_PROFILE = "prod"
        const val TEST_PROFILE = "test"

        val LOCAL_EXECUTION_PROFILES = setOf(
            LOCAL_NODB_PROFILE,
            "local-postgres",
            LOCAL_FIREBASE_PROFILE
        )

        val EXECUTION_PROFILES = LOCAL_EXECUTION_PROFILES + setOf(
            DEV_PROFILE,
            PROD_PROFILE,
            TEST_PROFILE
        )

        fun forActiveProfiles(vararg profiles: String): EnvironmentExposurePolicy =
            EnvironmentExposurePolicy(profiles.toSet())
    }
}
