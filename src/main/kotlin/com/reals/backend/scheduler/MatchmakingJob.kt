package com.reals.backend.scheduler

import com.reals.backend.service.MatchmakingProcessorService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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

    @param:Value("\${scheduler.matchmaking-job.max-pairs-per-run:5}")
    private val maxPairsPerRun: Int
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
        log.debug("MatchmakingJob - started maxPairsPerRun={}", maxPairsPerRun)

        val result =
            try {
                matchmakingProcessorService.process(
                    maxPairsPerRun = maxPairsPerRun
                )
            } catch (ex: RuntimeException) {
                log.error("MatchmakingJob - failed", ex)
                throw ex
            }

        if (result.candidatePairs == 0) {
            log.debug(
                "MatchmakingJob - completed candidatePairs=0 matchesCreated=0 failedPairs=0 durationMs={}",
                elapsedMs(startedAt)
            )
            return
        }

        log.info(
            "MatchmakingJob - completed candidatePairs={} matchesCreated={} failedPairs={} durationMs={}",
            result.candidatePairs,
            result.matchesCreated,
            result.failedPairs,
            elapsedMs(startedAt)
        )
    }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000
}
