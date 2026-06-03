package com.reals.backend.scheduler

import com.reals.backend.service.PenaltyService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class PenaltyExpirationJob(
    private val penaltyService: PenaltyService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString =
            "\${scheduler.penalty-expiration-job.fixed-delay}"
    )
    @SchedulerLock(name = "PenaltyExpirationJob", lockAtLeastFor = "PT30s", lockAtMostFor = "PT2M")
    fun run() {
        val startedAt = System.nanoTime()
        log.debug("PenaltyExpirationJob triggered")

        val expiredCount = penaltyService.expireOverduePenalties()

        log.logJobSummary(
            jobName = "PenaltyExpirationJob",
            summary = JobRunSummary(
                processed = expiredCount,
                succeeded = expiredCount,
                skipped = 0,
                failed = 0
            ),
            startedAt = startedAt
        )
    }
}
