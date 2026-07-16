package com.reals.backend.scheduler

import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.notification.VisualReviewReminderNotificationService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class VisualReviewReminderNotificationJob(
    private val visualReviewRepository: VisualReviewRepository,
    private val visualReviewReminderNotificationService: VisualReviewReminderNotificationService,

    @param:Value("\${scheduler.visual-review-reminder-job.fixed-delay:1800000}")
    private val fixedDelayMs: Long = 1800000
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.visual-review-reminder-job.fixed-delay}")
    @SchedulerLock(
        name = "VisualReviewReminderNotificationJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT10M"
    )
    fun run() {
        processVisualReviewReminders()
    }

    fun runNowForDev() {
        processVisualReviewReminders()
    }

    private fun processVisualReviewReminders() {
        require(fixedDelayMs > 0) {
            "scheduler.visual-review-reminder-job.fixed-delay must be positive"
        }

        val startedAt = System.nanoTime()
        val now = OffsetDateTime.now()
        val candidates = visualReviewRepository.findVisualReviewReminderCandidates(now)

        var succeeded = 0
        var skipped = 0
        var failed = 0

        candidates.forEach { candidate ->
            try {
                val result = visualReviewReminderNotificationService.processReminder(
                    matchId = candidate.matchId,
                    now = now
                )
                succeeded += result.succeeded
                skipped += result.skipped
                failed += result.failed
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "VisualReviewReminderNotificationJob - failed to process reminder match={}",
                    candidate.matchId,
                    ex
                )
            }
        }

        log.logJobSummary(
            jobName = "VisualReviewReminderNotificationJob",
            summary = JobRunSummary(
                processed = candidates.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            ),
            startedAt = startedAt
        )
    }
}
