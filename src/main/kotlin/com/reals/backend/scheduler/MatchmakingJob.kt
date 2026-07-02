package com.reals.backend.scheduler

import com.reals.backend.config.MatchmakingJobProperties
import com.reals.backend.service.matching.MatchmakingProcessorService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Processes queued users into first-chat matches.
 *
 * This is the production counterpart of the dev-only matchmaking process
 * endpoint. Both paths delegate to MatchmakingProcessorService.
 */
@Component
class MatchmakingJob(
    private val matchmakingProcessorService: MatchmakingProcessorService,
    private val properties: MatchmakingJobProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.matchmaking-job.fixed-delay:60000}")
    @SchedulerLock(
        name = "MatchmakingJob",
        lockAtLeastFor = "PT15S",
        lockAtMostFor = "PT2M"
    )
    fun run() {
        val startedAt = System.nanoTime()
        log.debug("MatchmakingJob - started maxPairsPerRun={}", properties.maxPairsPerRun)

        val result =
            try {
                matchmakingProcessorService.process(
                    maxPairsPerRun = properties.maxPairsPerRun
                )
            } catch (ex: RuntimeException) {
                log.error("MatchmakingJob - failed", ex)
                log.logJobSummary(
                    jobName = "MatchmakingJob",
                    summary = JobRunSummary(
                        processed = 0,
                        succeeded = 0,
                        skipped = 0,
                        failed = 1
                    ),
                    startedAt = startedAt
                )
                throw ex
            }

        log.logJobSummary(
            jobName = "MatchmakingJob",
            summary = JobRunSummary(
                processed = result.candidatePairs,
                succeeded = result.matchesCreated,
                skipped = (result.candidatePairs - result.matchesCreated - result.failedPairs)
                    .coerceAtLeast(0),
                failed = result.failedPairs
            ),
            startedAt = startedAt
        )
    }
}
