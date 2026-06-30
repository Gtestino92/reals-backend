package com.reals.backend.config.security.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "security.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val defaultCapacity: Int = 180,
    val defaultRefillTokens: Int = 180,
    val defaultRefillPeriodSeconds: Long = 60,
    val provisionCapacity: Int = 10,
    val provisionRefillTokens: Int = 10,
    val provisionRefillPeriodSeconds: Long = 60,
    val messageCapacity: Int = 60,
    val messageRefillTokens: Int = 60,
    val messageRefillPeriodSeconds: Long = 60,
    val profilePhotoCapacity: Int = 30,
    val profilePhotoRefillTokens: Int = 30,
    val profilePhotoRefillPeriodSeconds: Long = 60,
    val safetyReportCapacity: Int = 5,
    val safetyReportRefillTokens: Int = 5,
    val safetyReportRefillPeriodSeconds: Long = 86_400,
) {
    init {
        validateRule("default", defaultCapacity, defaultRefillTokens, defaultRefillPeriodSeconds)
        validateRule("provision", provisionCapacity, provisionRefillTokens, provisionRefillPeriodSeconds)
        validateRule("messages", messageCapacity, messageRefillTokens, messageRefillPeriodSeconds)
        validateRule("profile-photos", profilePhotoCapacity, profilePhotoRefillTokens, profilePhotoRefillPeriodSeconds)
        validateRule("safety-reports", safetyReportCapacity, safetyReportRefillTokens, safetyReportRefillPeriodSeconds)
    }

    private fun validateRule(
        name: String,
        capacity: Int,
        refillTokens: Int,
        refillPeriodSeconds: Long
    ) {
        require(capacity > 0) {
            "Rate limit capacity must be positive for $name"
        }
        require(refillTokens > 0) {
            "Rate limit refill tokens must be positive for $name"
        }
        require(refillPeriodSeconds > 0) {
            "Rate limit refill period must be positive for $name"
        }
    }
}
