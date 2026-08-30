package com.reals.backend.scheduler

import com.reals.backend.service.notification.MatchmakingAvailabilityNotificationService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

@Component
class MatchmakingAvailabilityNotificationJob(
    private val matchmakingAvailabilityNotificationService: MatchmakingAvailabilityNotificationService,
    private val transactionTemplate: TransactionTemplate,

    @param:Value("\${scheduler.matchmaking-availability-notification-job.fixed-delay:300000}")
    private val fixedDelayMs: Long = 300000,

    @param:Value("\${scheduler.matchmaking-availability-notification-job.batch-size:100}")
    private val batchSize: Int = 100,

    @param:Value("\${scheduler.matchmaking-availability-notification-job.discovery-batch-size:100}")
    private val discoveryBatchSize: Int = 100,
    private val schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.matchmaking-availability-notification-job.fixed-delay}")
    @SchedulerLock(
        name = "MatchmakingAvailabilityNotificationJob",
        lockAtLeastFor = "PT5S",
        lockAtMostFor = "PT4M"
    )
    fun run() {
        processMatchmakingAvailabilityNotifications()
    }

    fun runNowForDev() {
        processMatchmakingAvailabilityNotifications()
    }

    internal fun processMatchmakingAvailabilityNotifications(
        now: OffsetDateTime = OffsetDateTime.now()
    ): JobRunSummary {
        require(fixedDelayMs > 0) {
            "scheduler.matchmaking-availability-notification-job.fixed-delay must be positive"
        }
        require(batchSize > 0) {
            "scheduler.matchmaking-availability-notification-job.batch-size must be positive"
        }
        require(discoveryBatchSize > 0) {
            "scheduler.matchmaking-availability-notification-job.discovery-batch-size must be positive"
        }

        val startedAt = System.nanoTime()
        val discovered =
            matchmakingAvailabilityNotificationService.discoverMissingOrStaleEpisodes(
                now = now,
                maxUsers = discoveryBatchSize
            )

        val dueEpisodeIds =
            transactionTemplate.execute {
                matchmakingAvailabilityNotificationService.findDueEpisodeIds(
                    now = now,
                    batchSize = batchSize
                )
            }

        var succeeded = 0
        var skipped = 0
        var failed = 0

        dueEpisodeIds.forEach { episodeId ->
            try {
                val result = matchmakingAvailabilityNotificationService.processDueEpisode(
                    episodeId = episodeId,
                    now = now
                )
                succeeded += result.succeeded
                skipped += result.skipped
                failed += result.failed
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "MatchmakingAvailabilityNotificationJob - failed to process episode={}",
                    episodeId,
                    ex
                )
            }
        }

        val summary =
            JobRunSummary(
                processed = discovered + dueEpisodeIds.size,
                succeeded = succeeded,
                skipped = skipped + discovered,
                failed = failed
            )
        log.logJobSummary(
            jobName = "MatchmakingAvailabilityNotificationJob",
            summary = summary,
            startedAt = startedAt,
            schedulerMetrics = schedulerMetrics
        )
        return summary
    }
}
