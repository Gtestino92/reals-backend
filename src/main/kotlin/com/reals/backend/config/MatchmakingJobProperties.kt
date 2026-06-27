package com.reals.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "scheduler.matchmaking-job")
data class MatchmakingJobProperties(
    val fixedDelay: Long,
    val maxPairsPerRun: Int
) {
    init {
        require(fixedDelay > 0) {
            "scheduler.matchmaking-job.fixed-delay must be positive"
        }
        require(maxPairsPerRun > 0) {
            "scheduler.matchmaking-job.max-pairs-per-run must be positive"
        }
    }
}
