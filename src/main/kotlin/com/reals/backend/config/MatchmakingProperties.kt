package com.reals.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "matchmaking")
data class MatchmakingProperties(
    val excludePreviousPairing: Boolean = true,
    val previousPairingCooldownDays: Long = 30,
    val firstChatExpirationCooldownDays: Long = 7
) {
    init {
        require(previousPairingCooldownDays >= 0) {
            "matchmaking.previous-pairing-cooldown-days must be non-negative"
        }
        require(firstChatExpirationCooldownDays >= 0) {
            "matchmaking.first-chat-expiration-cooldown-days must be non-negative"
        }
    }
}
