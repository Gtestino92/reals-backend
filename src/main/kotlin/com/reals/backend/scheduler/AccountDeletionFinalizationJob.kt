package com.reals.backend.scheduler

import com.reals.backend.service.UserService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AccountDeletionFinalizationJob(
    private val userService: UserService,
    private val schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.account-deletion-finalization-job.fixed-delay}")
    @SchedulerLock(name = "AccountDeletionFinalizationJob", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    fun run() {
        finalizeAccountDeletions()
    }

    internal fun finalizeAccountDeletions(): JobRunSummary {
        val startedAt = System.nanoTime()
        val candidates = userService.findRecoverableAccountDeletionCandidates()

        var succeeded = 0
        var skipped = 0
        var failed = 0

        candidates.forEach { user ->
            try {
                if (userService.finalizeRecoverableAccountDeletion(user.id)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "AccountDeletionFinalizationJob - failed to finalize user={}",
                    user.id,
                    ex
                )
            }
        }

        val summary = JobRunSummary(
            processed = candidates.size,
            succeeded = succeeded,
            skipped = skipped,
            failed = failed
        )

        log.logJobSummary(
            jobName = "AccountDeletionFinalizationJob",
            summary = summary,
            startedAt = startedAt,
            schedulerMetrics = schedulerMetrics
        )

        return summary
    }

    fun runNowForDev() {
        run()
    }
}
