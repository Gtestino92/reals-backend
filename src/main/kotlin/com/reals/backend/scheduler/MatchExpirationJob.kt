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

        if (expiredCandidates.isEmpty()) {
            log.debug("MatchExpirationJob - no expired matches found")
        } else {
            log.info(
                "MatchExpirationJob - found {} match(es) to expire",
                expiredCandidates.size
            )

            expiredCandidates.forEach { match ->
                try {
                    matchService.expireMatch(match.id)

                    log.info(
                        "MatchExpirationJob - expired match={} (createdAt={})",
                        match.id,
                        match.createdAt
                    )
                } catch (ex: Exception) {
                    log.error(
                        "MatchExpirationJob - failed to expire match={}",
                        match.id,
                        ex
                    )
                }
            }
        }

        val expiredReviews =
            visualReviewRepository.findByExpiresAtBefore(
                expiresAt = OffsetDateTime.now()
            )

        if (expiredReviews.isNotEmpty()) {
            log.info(
                "MatchExpirationJob (fallback) - found {} expired visual review(s)",
                expiredReviews.size
            )

            expiredReviews.forEach { review ->
                try {
                    matchService.expireMatch(review.matchId)

                    log.info(
                        "MatchExpirationJob (fallback) - expired match={}",
                        review.matchId
                    )
                } catch (ex: Exception) {
                    log.warn(
                        "MatchExpirationJob (fallback) - skipped match={}",
                        review.matchId,
                        ex
                    )
                }
            }
        }
    }
}
