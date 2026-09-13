package com.reals.backend.config.security.ratelimit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RateLimitMetricsTest {

    @Test
    fun `rate limit metrics use bounded operational tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)

        metrics.recordDecision(
            phase = RateLimitMetrics.PRE_AUTH,
            group = RateLimitGroup.PROVISION.id,
            outcome = RateLimitMetrics.ALLOWED
        )
        metrics.recordDecision(
            phase = RateLimitMetrics.POST_AUTH,
            group = RateLimitGroup.SAFETY_REPORTS.id,
            outcome = RateLimitMetrics.REJECTED
        )

        assertEquals(
            1.0,
            registry.get(MicrometerRateLimitMetrics.REQUESTS)
                .tag("phase", "pre_auth")
                .tag("group", "provision")
                .tag("outcome", "allowed")
                .counter()
                .count()
        )
        assertEquals(
            1.0,
            registry.get(MicrometerRateLimitMetrics.REQUESTS)
                .tag("phase", "post_auth")
                .tag("group", "safety-reports")
                .tag("outcome", "rejected")
                .counter()
                .count()
        )
    }
}
