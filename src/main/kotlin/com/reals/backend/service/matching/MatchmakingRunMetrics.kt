package com.reals.backend.service.matching

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

interface MatchmakingRunMetrics {
    fun recordLimitExhausted(limitExhausted: Boolean)

    companion object {
        fun noop(): MatchmakingRunMetrics = NoopMatchmakingRunMetrics
    }
}

private object NoopMatchmakingRunMetrics : MatchmakingRunMetrics {
    override fun recordLimitExhausted(limitExhausted: Boolean) = Unit
}

@Component
class MicrometerMatchmakingRunMetrics(
    meterRegistry: MeterRegistry
) : MatchmakingRunMetrics {

    private val limitExhausted = AtomicInteger(0)

    init {
        Gauge.builder(LIMIT_EXHAUSTED, limitExhausted) { it.get().toDouble() }
            .register(meterRegistry)
    }

    override fun recordLimitExhausted(limitExhausted: Boolean) {
        this.limitExhausted.set(if (limitExhausted) 1 else 0)
    }

    companion object {
        const val LIMIT_EXHAUSTED = "reals.matchmaking.run.limit_exhausted"
    }
}
