package com.reals.backend.config.environment

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

@Component
class ExecutionProfileCompatibilityGuard(
    private val environmentExposurePolicy: EnvironmentExposurePolicy
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        environmentExposurePolicy.validateCompatibleExecutionProfiles()
    }
}
