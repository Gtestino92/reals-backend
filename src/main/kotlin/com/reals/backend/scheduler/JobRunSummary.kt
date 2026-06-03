package com.reals.backend.scheduler

import org.slf4j.Logger

internal data class JobRunSummary(
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
    startedAt: Long
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
}
