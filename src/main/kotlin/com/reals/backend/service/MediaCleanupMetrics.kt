package com.reals.backend.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

interface MediaCleanupMetrics {
    fun recordFailedTaskCount(count: Long)

    companion object {
        fun noop(): MediaCleanupMetrics = NoopMediaCleanupMetrics
    }
}

private object NoopMediaCleanupMetrics : MediaCleanupMetrics {
    override fun recordFailedTaskCount(count: Long) = Unit
}

@Component
class MicrometerMediaCleanupMetrics(
    meterRegistry: MeterRegistry
) : MediaCleanupMetrics {

    private val failedTasks = AtomicLong(0)

    init {
        Gauge.builder(FAILED_TASKS, failedTasks) { it.get().toDouble() }
            .register(meterRegistry)
    }

    override fun recordFailedTaskCount(count: Long) {
        failedTasks.set(count)
    }

    companion object {
        const val FAILED_TASKS = "reals.media_cleanup.failed_tasks"
    }
}
