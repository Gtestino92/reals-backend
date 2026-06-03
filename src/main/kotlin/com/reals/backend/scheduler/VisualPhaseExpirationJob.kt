package com.reals.backend.scheduler

import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.MatchService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Expires visual reviews whose visual phase deadline has passed.
 *
 * Runs every 5 minutes. Config key:
 * scheduler.visual-phase-expiration-job.fixed-delay
 */
@Component
class VisualPhaseExpirationJob(

    private val visualReviewRepository: VisualReviewRepository,
    private val matchService: MatchService

) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.visual-phase-expiration-job.fixed-delay}")
    @SchedulerLock(
        name = "VisualPhaseExpirationJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT3M"
    )
    fun run() {
        val startedAt = System.nanoTime()
        log.debug("VisualPhaseExpirationJob triggered")

        val expired =
            visualReviewRepository.findByExpiresAtBefore(
                expiresAt = OffsetDateTime.now()
            )

        var succeeded = 0
        var skipped = 0
        var failed = 0

        expired.forEach { review ->
            try {
                val changed = matchService.expireMatch(review.matchId)
                if (changed) {
                    succeeded += 1
                    log.info(
                        "VisualPhaseExpirationJob - expired match={}",
                        review.matchId
                    )
                } else {
                    skipped += 1
                    log.debug(
                        "VisualPhaseExpirationJob - skipped match={} because it was already expired",
                        review.matchId
                    )
                }
            } catch (ex: Exception) {
                log.error(
                    "VisualPhaseExpirationJob - failed to expire match={}",
                    review.matchId,
                    ex
                )
                failed += 1
            }
        }

        log.logJobSummary(
            jobName = "VisualPhaseExpirationJob",
            summary = JobRunSummary(
                processed = expired.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            ),
            startedAt = startedAt
        )
    }
}
