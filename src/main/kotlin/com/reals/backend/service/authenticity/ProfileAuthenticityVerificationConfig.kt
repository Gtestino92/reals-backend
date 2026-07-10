package com.reals.backend.service.authenticity

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "profile.authenticity-verification.policy")
data class ProfileAuthenticityPolicyProperties(
    val minMatchedPersonPhotos: Int = 3,
    val maxContradictoryPersonPhotos: Int = 0
) {
    init {
        require(minMatchedPersonPhotos > 0) {
            "profile.authenticity-verification.policy.min-matched-person-photos must be positive"
        }
        require(maxContradictoryPersonPhotos >= 0) {
            "profile.authenticity-verification.policy.max-contradictory-person-photos must not be negative"
        }
    }
}

@Configuration
@EnableConfigurationProperties(ProfileAuthenticityPolicyProperties::class)
class ProfileAuthenticityVerificationConfig
