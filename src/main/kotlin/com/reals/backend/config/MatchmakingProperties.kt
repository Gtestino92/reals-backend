package com.reals.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "matchmaking")
data class MatchmakingProperties(
    val allowActivePairDuplicates: Boolean = false,
    val excludePreviousPairing: Boolean = true,
    val previousPairingCooldownDays: Long = 30,
    val firstChatExpirationCooldownDays: Long = 7,
    val firstChatDecisionMismatchCooldownDays: Long = 7,
    val visualAdvancement: VisualAdvancementProperties = VisualAdvancementProperties()
) {
    init {
        require(previousPairingCooldownDays >= 0) {
            "matchmaking.previous-pairing-cooldown-days must be non-negative"
        }
        require(firstChatExpirationCooldownDays >= 0) {
            "matchmaking.first-chat-expiration-cooldown-days must be non-negative"
        }
        require(firstChatDecisionMismatchCooldownDays >= 0) {
            "matchmaking.first-chat-decision-mismatch-cooldown-days must be non-negative"
        }
    }
}

data class VisualAdvancementProperties(
    val maxPerWindow: Int = 10,
    val windowHours: Long = 24
) {
    init {
        require(maxPerWindow > 0) {
            "matchmaking.visual-advancement.max-per-window must be greater than 0"
        }
        require(windowHours > 0) {
            "matchmaking.visual-advancement.window-hours must be greater than 0"
        }
    }
}
