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

    @param:Value("\${scheduler.matchmaking-job.batch-size:5}")
    private val batchSize: Int
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.matchmaking-job.fixed-delay:60000}")
    @SchedulerLock(
        name = "MatchmakingJob",
        lockAtLeastFor = "PT15S",
        lockAtMostFor = "PT2M"
    )
    fun run() {
        val result =
            matchmakingProcessorService.processBatch(
                batchSize = batchSize
            )

        if (result.candidatePairs == 0) {
            log.debug("MatchmakingJob - no candidate pairs found")
            return
        }

        log.info(
            "MatchmakingJob - candidatePairs={} matchesCreated={} failedPairs={}",
            result.candidatePairs,
            result.matchesCreated,
            result.failedPairs
        )
    }
}
