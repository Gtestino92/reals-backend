package com.reals.backend.service.reputation

/**
 * Trust score in range [0.0, 1.0].
 *
 * Current uses:
 * - Penalty duration scaling: lower score => longer penalty.
 *
 * Future uses:
 * - Matchmaking commitment filter: skip users below a configured threshold.
 * - Progressive penalty caps: max duration per tier.
 */
data class TrustScore(
    val value: Double
) {
    init {
        require(value in 0.0..1.0) {
            "TrustScore must be between 0.0 and 1.0, got $value"
        }
    }

    /**
     * Multiplier applied to the base penalty duration.
     *
     * score >= 0.8 -> 1x
     * score >= 0.5 -> 2x
     * score < 0.5  -> 3x
     */
    fun penaltyMultiplier(): Double =
        when {
            value >= 0.8 -> 1.0
            value >= 0.5 -> 2.0
            else -> 3.0
        }

    companion object {
        val NEUTRAL = TrustScore(1.0)
    }
}
