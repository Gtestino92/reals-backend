package com.reals.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "account.ban")
data class AccountBanProperties(
    val temporaryResumeMarginMinutes: Long = 30
) {
    init {
        require(temporaryResumeMarginMinutes >= 0) {
            "account.ban.temporary-resume-margin-minutes must be non-negative"
        }
    }
}
