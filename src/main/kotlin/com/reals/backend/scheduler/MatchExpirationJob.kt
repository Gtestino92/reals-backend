package com.reals.backend.scheduler

import com.reals.backend.domain.MatchState
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.MatchService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Expires matches that have exceeded their allowed duration without progressing.
 *
 * Primary: CHAT_ACTIVE matches stuck beyond [maxChatDuration] (default PT24H).
 * Fallback: VISUAL_PHASE matches whose visual_reviews.expires_at is past — acts as safety net
 * in case VisualPhaseExpirationJob misses a record.
 *
 * Config key: scheduler.match-expiration-job.max-chat-duration
 */
@Component
class MatchExpirationJob(

    private val matchRepository: MatchRepository,
    private val visualReviewRepository: VisualReviewRepository,
    private val matchService: MatchService,

    @param:Value("\${scheduler.match-expiration-job.max-chat-duration:PT24H}")
    private val maxChatDuration: Duration

) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.match-expiration-job.fixed-delay}")
    @SchedulerLock(
        name = "MatchExpirationJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT2M"
    )
    fun run() {
        val startedAt = System.nanoTime()
        var processed = 0
        var succeeded = 0
        var skipped = 0
        var failed = 0

        log.debug(
            "MatchExpirationJob triggered - maxChatDuration={}",
            maxChatDuration
        )

        val cutoff = OffsetDateTime.now().minus(maxChatDuration)

        val expiredCandidates =
            matchRepository.findByStateAndCreatedAtBefore(
                state = MatchState.CHAT_ACTIVE,
                createdAtBefore = cutoff
            )

        expiredCandidates.forEach { match ->
            processed += 1
            try {
                val changed = matchService.expireMatch(match.id)
                if (changed) {
                    succeeded += 1
                    log.info(
                        "MatchExpirationJob - expired match={} (createdAt={})",
                        match.id,
                        match.createdAt
                    )
                } else {
                    skipped += 1
                    log.debug(
                        "MatchExpirationJob - skipped match={} because it was already expired",
                        match.id
                    )
                }
            } catch (ex: Exception) {
                log.error(
                    "MatchExpirationJob - failed to expire match={}",
                    match.id,
                    ex
                )
                failed += 1
            }
        }

        val expiredReviews =
            visualReviewRepository.findByExpiresAtBefore(
                expiresAt = OffsetDateTime.now()
            )

        if (expiredReviews.isNotEmpty()) {
            expiredReviews.forEach { review ->
                processed += 1
                try {
                    val changed = matchService.expireMatch(review.matchId)
                    if (changed) {
                        succeeded += 1
                        log.info(
                            "MatchExpirationJob (fallback) - expired match={}",
                            review.matchId
                        )
                    } else {
                        skipped += 1
                        log.debug(
                            "MatchExpirationJob (fallback) - skipped match={} because it was already expired",
                            review.matchId
                        )
                    }
                } catch (ex: Exception) {
                    log.error(
                        "MatchExpirationJob (fallback) - failed to expire match={}",
                        review.matchId,
                        ex
                    )
                    failed += 1
                }
            }
        }

        log.logJobSummary(
            jobName = "MatchExpirationJob",
            summary = JobRunSummary(
                processed = processed,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            ),
            startedAt = startedAt
        )
    }
}
