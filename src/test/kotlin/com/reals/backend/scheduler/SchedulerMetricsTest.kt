package com.reals.backend.scheduler

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SchedulerMetricsTest {

    @Test
    fun `scheduler metrics record run outcomes item counts duration and backlog`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerSchedulerMetrics(registry)
        val startedAt = System.nanoTime()

        metrics.recordJobRun(
            jobName = "MediaCleanupJob",
            summary = JobRunSummary(
                processed = 4,
                succeeded = 2,
                skipped = 1,
                failed = 1
            ),
            startedAt = startedAt,
            backlogRemaining = true
        )
        metrics.recordJobRun(
            jobName = "MediaCleanupJob",
            summary = JobRunSummary(
                processed = 0,
                succeeded = 0,
                skipped = 0,
                failed = 0
            ),
            startedAt = System.nanoTime(),
            backlogRemaining = false
        )

        assertEquals(
            1.0,
            registry.get(MicrometerSchedulerMetrics.RUNS)
                .tag("job", "MediaCleanupJob")
                .tag("outcome", "partial_failure")
                .counter()
                .count()
        )
        assertEquals(
            1.0,
            registry.get(MicrometerSchedulerMetrics.RUNS)
                .tag("job", "MediaCleanupJob")
                .tag("outcome", "success")
                .counter()
                .count()
        )
        assertEquals(
            4.0,
            registry.get(MicrometerSchedulerMetrics.ITEMS)
                .tag("job", "MediaCleanupJob")
                .tag("result", MicrometerSchedulerMetrics.PROCESSED)
                .summary()
                .totalAmount()
        )
        assertEquals(
            2.0,
            registry.get(MicrometerSchedulerMetrics.ITEMS)
                .tag("job", "MediaCleanupJob")
                .tag("result", MicrometerSchedulerMetrics.SUCCEEDED)
                .summary()
                .totalAmount()
        )
        assertEquals(
            1.0,
            registry.get(MicrometerSchedulerMetrics.ITEMS)
                .tag("job", "MediaCleanupJob")
                .tag("result", MicrometerSchedulerMetrics.SKIPPED)
                .summary()
                .totalAmount()
        )
        assertEquals(
            1.0,
            registry.get(MicrometerSchedulerMetrics.ITEMS)
                .tag("job", "MediaCleanupJob")
                .tag("result", MicrometerSchedulerMetrics.FAILED)
                .summary()
                .totalAmount()
        )
        assertEquals(
            0.0,
            registry.get(MicrometerSchedulerMetrics.BACKLOG_REMAINING)
                .tag("job", "MediaCleanupJob")
                .gauge()
                .value()
        )
        assertNotNull(
            registry.get(MicrometerSchedulerMetrics.DURATION)
                .tag("job", "MediaCleanupJob")
                .tag("outcome", "partial_failure")
                .timer()
        )
    }
}
