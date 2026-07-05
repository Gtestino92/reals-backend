package com.reals.backend.scheduler

import com.reals.backend.service.reliability.UserReliabilityScoreService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class UserReliabilityEventCleanupJob(
    private val userReliabilityScoreService: UserReliabilityScoreService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.user-reliability-cleanup-job.fixed-delay}")
    @SchedulerLock(
        name = "UserReliabilityEventCleanupJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT5M"
    )
    fun run() {
        processExpiredEvents()
    }

    fun runNowForDev() {
        processExpiredEvents()
    }

    internal fun processExpiredEvents(): JobRunSummary {
        val startedAt = System.nanoTime()
        val deleted = userReliabilityScoreService.deleteExpiredEvents()
        val summary = JobRunSummary(
            processed = deleted,
            succeeded = deleted,
            skipped = 0,
            failed = 0
        )

        log.logJobSummary(
            jobName = "UserReliabilityEventCleanupJob",
            summary = summary,
            startedAt = startedAt
        )

        return summary
    }
}
