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
        processExpiredPenalties()
    }

    internal fun processExpiredPenalties(): JobRunSummary {
        val startedAt = System.nanoTime()
        log.debug("PenaltyExpirationJob triggered")

        val expired = penaltyService.findExpiredActivePenalties()
        var succeeded = 0
        var skipped = 0
        var failed = 0

        expired.forEach { penalty ->
            try {
                if (penaltyService.expireOverduePenalty(penalty.id)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "PenaltyExpirationJob - failed to expire penalty={}",
                    penalty.id,
                    ex
                )
            }
        }

        val summary = JobRunSummary(
            processed = expired.size,
            succeeded = succeeded,
            skipped = skipped,
            failed = failed
        )

        log.logJobSummary(
            jobName = "PenaltyExpirationJob",
            summary = summary,
            startedAt = startedAt
        )

        return summary
    }
}
