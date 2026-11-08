package com.reals.backend.scheduler

import com.reals.backend.service.AccountProvisioningLimiter
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class AccountProvisioningCleanupJob(
    private val limiter: AccountProvisioningLimiter,
    private val schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop()
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    @Scheduled(fixedDelayString = "\${scheduler.account-provisioning-cleanup-job.fixed-delay:3600000}")
    @SchedulerLock(name = "AccountProvisioningCleanupJob", lockAtMostFor = "PT5M")
    fun run() {
        val startedAt = System.nanoTime()
        val deleted = limiter.cleanup()
        log.logJobSummary(
            jobName = "AccountProvisioningCleanupJob",
            summary = JobRunSummary(processed = deleted, succeeded = deleted, skipped = 0, failed = 0),
            startedAt = startedAt,
            schedulerMetrics = schedulerMetrics
        )
    }
}
