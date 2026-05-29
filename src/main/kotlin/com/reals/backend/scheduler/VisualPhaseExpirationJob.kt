package com.reals.backend.scheduler

import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.MatchService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
    @Transactional
    fun run() {
        log.debug("VisualPhaseExpirationJob triggered")

        val expired =
            visualReviewRepository.findByExpiresAtBefore(
                expiresAt = OffsetDateTime.now()
            )

        if (expired.isEmpty()) {
            log.debug("VisualPhaseExpirationJob - no expired visual reviews found")
            return
        }

        log.info(
            "VisualPhaseExpirationJob - found {} expired visual review(s)",
            expired.size
        )

        expired.forEach { review ->
            try {
                matchService.expireMatch(review.matchId)

                log.info(
                    "VisualPhaseExpirationJob - expired match={}",
                    review.matchId
                )
            } catch (ex: Exception) {
                log.warn(
                    "VisualPhaseExpirationJob - skipped match={}",
                    review.matchId,
                    ex
                )
            }
        }
    }
}
