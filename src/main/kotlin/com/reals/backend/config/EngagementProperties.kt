package com.reals.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "engagement")
data class EngagementProperties(
    val maxActiveMatches: Int = 5,
    val maxActiveConnections: Int = 4,
    val reliabilityCapacity: ReliabilityCapacityProperties = ReliabilityCapacityProperties()
) {
    init {
        require(maxActiveMatches > 0) {
            "engagement.max-active-matches must be greater than 0"
        }
        require(maxActiveConnections > 0) {
            "engagement.max-active-connections must be greater than 0"
        }
        reliabilityCapacity.match.validate(
            prefix = "engagement.reliability-capacity.match",
            baseline = maxActiveMatches,
            baselineProperty = "engagement.max-active-matches"
        )
        reliabilityCapacity.connection.validate(
            prefix = "engagement.reliability-capacity.connection",
            baseline = maxActiveConnections,
            baselineProperty = "engagement.max-active-connections"
        )
    }
}

data class ReliabilityCapacityProperties(
    val match: ReliabilityCapacityCurveProperties = ReliabilityCapacityCurveProperties(
        min = 3,
        max = 9,
        rewardScale = 20.0,
        penaltyScale = 10.0
    ),
    val connection: ReliabilityCapacityCurveProperties = ReliabilityCapacityCurveProperties(
        min = 2,
        max = 6,
        rewardScale = 30.0,
        penaltyScale = 10.0
    )
)

data class ReliabilityCapacityCurveProperties(
    val min: Int,
    val max: Int,
    val rewardScale: Double,
    val penaltyScale: Double
) {
    fun validate(
        prefix: String,
        baseline: Int,
        baselineProperty: String
    ) {
        require(min > 0) {
            "$prefix.min must be greater than 0"
        }
        require(max >= min) {
            "$prefix.max must be greater than or equal to min"
        }
        require(baseline in min..max) {
            "$baselineProperty must be within $prefix.min and $prefix.max"
        }
        require(rewardScale.isFinite() && rewardScale > 0.0) {
            "$prefix.reward-scale must be finite and greater than 0"
        }
        require(penaltyScale.isFinite() && penaltyScale > 0.0) {
            "$prefix.penalty-scale must be finite and greater than 0"
        }
    }
}
