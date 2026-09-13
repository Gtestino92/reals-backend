package com.reals.backend.config.security.ratelimit

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

interface RateLimitMetrics {
    fun recordDecision(
        phase: String,
        group: String,
        outcome: String
    )

    companion object {
        const val PRE_AUTH = "pre_auth"
        const val POST_AUTH = "post_auth"
        const val ALLOWED = "allowed"
        const val REJECTED = "rejected"

        fun noop(): RateLimitMetrics = NoopRateLimitMetrics
    }
}

private object NoopRateLimitMetrics : RateLimitMetrics {
    override fun recordDecision(
        phase: String,
        group: String,
        outcome: String
    ) = Unit
}

@Component
class MicrometerRateLimitMetrics(
    private val meterRegistry: MeterRegistry
) : RateLimitMetrics {

    override fun recordDecision(
        phase: String,
        group: String,
        outcome: String
    ) {
        Counter.builder(REQUESTS)
            .tag(PHASE, phase)
            .tag(GROUP, group)
            .tag(OUTCOME, outcome)
            .register(meterRegistry)
            .increment()
    }

    companion object {
        const val REQUESTS = "reals.rate_limit.requests"

        private const val PHASE = "phase"
        private const val GROUP = "group"
        private const val OUTCOME = "outcome"
    }
}
