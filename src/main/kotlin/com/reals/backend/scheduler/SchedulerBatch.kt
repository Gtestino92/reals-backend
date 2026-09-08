package com.reals.backend.scheduler

import org.slf4j.Logger

internal data class SchedulerBatch<T>(
    val items: List<T>,
    val fetched: Int,
    val backlogRemaining: Boolean
)

internal fun <T> boundedSchedulerBatch(
    fetchedCandidates: List<T>,
    batchSize: Int
): SchedulerBatch<T> {
    require(batchSize > 0) { "scheduler job batch-size must be positive" }
    return SchedulerBatch(
        items = fetchedCandidates.take(batchSize),
        fetched = fetchedCandidates.size,
        backlogRemaining = fetchedCandidates.size > batchSize
    )
}

internal fun Logger.logBatchComplete(
    jobName: String,
    batchSize: Int,
    fetched: Int,
    backlogRemaining: Boolean
) {
    info(
        "{} - batch complete batchSize={} fetched={} backlogRemaining={}",
        jobName,
        batchSize,
        fetched,
        backlogRemaining
    )
}
