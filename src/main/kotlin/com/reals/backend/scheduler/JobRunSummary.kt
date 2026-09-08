package com.reals.backend.scheduler

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class JobRunSummary(
    val processed: Int,
    val succeeded: Int,
    val skipped: Int,
    val failed: Int
)

internal fun elapsedMs(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1_000_000

internal fun Logger.logJobSummary(
    jobName: String,
    summary: JobRunSummary,
    startedAt: Long,
    schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop(),
    backlogRemaining: Boolean? = null
) {
    val message = "{} - completed processed={} succeeded={} skipped={} failed={} durationMs={}"
    val arguments: Array<Any> = arrayOf(
        jobName,
        summary.processed,
        summary.succeeded,
        summary.skipped,
        summary.failed,
        elapsedMs(startedAt)
    )

    if (summary.processed == 0 && summary.failed == 0) {
        debug(message, *arguments)
    } else {
        info(message, *arguments)
    }

    schedulerMetrics.recordJobRun(
        jobName = jobName,
        summary = summary,
        startedAt = startedAt,
        backlogRemaining = backlogRemaining
    )
}

interface SchedulerMetrics {
    fun recordJobRun(
        jobName: String,
        summary: JobRunSummary,
        startedAt: Long,
        backlogRemaining: Boolean? = null
    )

    companion object {
        fun noop(): SchedulerMetrics = NoopSchedulerMetrics
    }
}

private object NoopSchedulerMetrics : SchedulerMetrics {
    override fun recordJobRun(
        jobName: String,
        summary: JobRunSummary,
        startedAt: Long,
        backlogRemaining: Boolean?
    ) = Unit
}

@Component
class MicrometerSchedulerMetrics(
    private val meterRegistry: MeterRegistry
) : SchedulerMetrics {

    private val backlogGauges = ConcurrentHashMap<String, AtomicInteger>()

    override fun recordJobRun(
        jobName: String,
        summary: JobRunSummary,
        startedAt: Long,
        backlogRemaining: Boolean?
    ) {
        val outcome = outcome(summary)
        Counter.builder(RUNS)
            .tag(JOB, jobName)
            .tag(OUTCOME, outcome)
            .register(meterRegistry)
            .increment()

        Timer.builder(DURATION)
            .tag(JOB, jobName)
            .tag(OUTCOME, outcome)
            .register(meterRegistry)
            .record(Duration.ofNanos(System.nanoTime() - startedAt))

        recordItems(jobName, PROCESSED, summary.processed)
        recordItems(jobName, SUCCEEDED, summary.succeeded)
        recordItems(jobName, SKIPPED, summary.skipped)
        recordItems(jobName, FAILED, summary.failed)

        backlogRemaining?.let { updateBacklogGauge(jobName, it) }
    }

    private fun recordItems(
        jobName: String,
        result: String,
        count: Int
    ) {
        DistributionSummary.builder(ITEMS)
            .tag(JOB, jobName)
            .tag(RESULT, result)
            .register(meterRegistry)
            .record(count.toDouble())
    }

    private fun updateBacklogGauge(
        jobName: String,
        backlogRemaining: Boolean
    ) {
        val gauge = backlogGauges.computeIfAbsent(jobName) {
            AtomicInteger().also { value ->
                Gauge.builder(BACKLOG_REMAINING, value) { it.get().toDouble() }
                    .tag(JOB, jobName)
                    .register(meterRegistry)
            }
        }
        gauge.set(if (backlogRemaining) 1 else 0)
    }

    private fun outcome(summary: JobRunSummary): String =
        if (summary.failed > 0) PARTIAL_FAILURE else SUCCESS

    companion object {
        const val RUNS = "reals.scheduler.job.runs"
        const val DURATION = "reals.scheduler.job.duration"
        const val ITEMS = "reals.scheduler.job.items"
        const val BACKLOG_REMAINING = "reals.scheduler.job.backlog_remaining"

        const val PROCESSED = "processed"
        const val SUCCEEDED = "succeeded"
        const val SKIPPED = "skipped"
        const val FAILED = "failed"

        private const val JOB = "job"
        private const val OUTCOME = "outcome"
        private const val RESULT = "result"
        private const val SUCCESS = "success"
        private const val PARTIAL_FAILURE = "partial_failure"
    }
}
