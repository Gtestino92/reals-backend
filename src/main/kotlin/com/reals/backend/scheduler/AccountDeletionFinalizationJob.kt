package com.reals.backend.scheduler

import com.reals.backend.service.UserService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AccountDeletionFinalizationJob(
    private val userService: UserService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.account-deletion-finalization-job.fixed-delay}")
    @SchedulerLock(name = "AccountDeletionFinalizationJob", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    fun run() {
        val startedAt = System.nanoTime()
        var finalized = 0
        var failed = 0

        try {
            finalized = userService.finalizeRecoverableAccountDeletions()
        } catch (ex: Exception) {
            failed = 1
            log.error("AccountDeletionFinalizationJob - failed to finalize account deletions", ex)
        }

        log.logJobSummary(
            jobName = "AccountDeletionFinalizationJob",
            summary = JobRunSummary(
                processed = finalized + failed,
                succeeded = finalized,
                skipped = 0,
                failed = failed
            ),
            startedAt = startedAt
        )
    }

    fun runNowForDev() {
        run()
    }
}
