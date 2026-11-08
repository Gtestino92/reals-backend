package com.reals.backend.config.security.ratelimit

import java.time.Duration
import java.time.Instant

class TokenBucket(
    private val capacity: Int,
    private val refillTokens: Int,
    private val refillPeriod: Duration,
    now: Instant
) {
    internal val idleRetentionNanos: Long = try {
        val periodsToFill = (capacity.toLong() + refillTokens - 1) / refillTokens
        maxOf(Duration.ofMinutes(15), refillPeriod.multipliedBy(periodsToFill)).toNanos()
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private var availableTokens = capacity
    private var lastRefillAt = now

    @Synchronized
    fun tryConsume(now: Instant): RateLimitDecision {
        refill(now)

        if (availableTokens > 0) {
            availableTokens -= 1
            return RateLimitDecision.Allowed(
                remainingTokens = availableTokens
            )
        }

        return RateLimitDecision.Rejected(
            retryAfterSeconds = retryAfterSeconds(now)
        )
    }

    private fun refill(now: Instant) {
        if (refillTokens <= 0 || refillPeriod.isZero || refillPeriod.isNegative) {
            return
        }

        val elapsedPeriods = Duration.between(lastRefillAt, now)
            .toMillis() / refillPeriod.toMillis()

        if (elapsedPeriods <= 0) {
            return
        }

        // Saturate before multiplication/narrowing, including after long idle periods.
        val periodsToFill = (capacity.toLong() + refillTokens - 1) / refillTokens
        val tokensToAdd = minOf(elapsedPeriods, periodsToFill) * refillTokens
        availableTokens = minOf(capacity.toLong(), availableTokens.toLong() + tokensToAdd).toInt()
        lastRefillAt = lastRefillAt.plus(refillPeriod.multipliedBy(elapsedPeriods))
    }

    private fun retryAfterSeconds(now: Instant): Long {
        val nextRefillAt = lastRefillAt.plus(refillPeriod)
        return maxOf(
            1,
            Duration.between(now, nextRefillAt).seconds
        )
    }
}

sealed class RateLimitDecision {
    data class Allowed(
        val remainingTokens: Int
    ) : RateLimitDecision()

    data class Rejected(
        val retryAfterSeconds: Long
    ) : RateLimitDecision()
}
