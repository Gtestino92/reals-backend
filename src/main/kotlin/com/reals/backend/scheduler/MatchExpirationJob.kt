package com.reals.backend.scheduler

import com.reals.backend.domain.MatchState
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.MatchService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Expires matches that have exceeded their allowed duration without progressing.
 *
 * Primary: CHAT_ACTIVE matches stuck beyond [maxChatDuration] (default PT20M).
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

    @param:Value("\${scheduler.match-expiration-job.max-chat-duration:PT20M}")
    private val maxChatDuration: Duration,

    @param:Value("\${scheduler.match-expiration-job.batch-size:100}")
    private val batchSize: Int = 100,
    private val schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop()

) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.match-expiration-job.fixed-delay}")
    @SchedulerLock(
        name = "MatchExpirationJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT5M"
    )
    fun run() {
        processMatchExpirations()
    }

    internal fun processMatchExpirations(): JobRunSummary {
        require(batchSize > 0) { "scheduler.match-expiration-job.batch-size must be positive" }
        val startedAt = System.nanoTime()
        var processed = 0
        var succeeded = 0
        var skipped = 0
        var failed = 0
        var fetched = 0
        var backlogRemaining = false

        log.debug(
            "MatchExpirationJob triggered - maxChatDuration={}",
            maxChatDuration
        )

        val now = OffsetDateTime.now()
        val cutoff = now.minus(maxChatDuration)

        val expiredCandidates =
            boundedSchedulerBatch(
                fetchedCandidates = matchRepository.findIdsByStateAndCreatedAtBefore(
                state = MatchState.CHAT_ACTIVE,
                    createdAtBefore = cutoff,
                    pageable = PageRequest.of(0, batchSize + 1)
                ),
                batchSize = batchSize
            )
        fetched += expiredCandidates.fetched
        backlogRemaining = backlogRemaining || expiredCandidates.backlogRemaining

        expiredCandidates.items.forEach { matchId ->
            processed += 1
            try {
                val changed = matchService.expireMatch(matchId)
                if (changed) {
                    succeeded += 1
                    log.info(
                        "MatchExpirationJob - expired match={}",
                        matchId
                    )
                } else {
                    skipped += 1
                    log.debug(
                        "MatchExpirationJob - skipped match={} because it was already expired",
                        matchId
                    )
                }
            } catch (ex: Exception) {
                log.error(
                    "MatchExpirationJob - failed to expire match={}",
                    matchId,
                    ex
                )
                failed += 1
            }
        }

        val remainingCapacity = batchSize - processed
        val expiredReviews =
            if (remainingCapacity > 0) {
                boundedSchedulerBatch(
                    fetchedCandidates = visualReviewRepository.findExpiredMatchIds(
                        expiresAt = now,
                        pageable = PageRequest.of(0, remainingCapacity + 1)
                    ),
                    batchSize = remainingCapacity
                )
            } else {
                backlogRemaining = true
                SchedulerBatch(emptyList(), 0, true)
            }
        fetched += expiredReviews.fetched
        backlogRemaining = backlogRemaining || expiredReviews.backlogRemaining

        if (expiredReviews.items.isNotEmpty()) {
            expiredReviews.items.forEach { matchId ->
                processed += 1
                try {
                    val match = matchService.findByIdOrThrow(matchId)
                    if (match.state != MatchState.VISUAL_PHASE) {
                        skipped += 1
                        log.debug(
                            "MatchExpirationJob (fallback) - skipped match={} because state={}",
                            matchId,
                            match.state
                        )
                        return@forEach
                    }

                    val changed = matchService.expireMatch(matchId)
                    if (changed) {
                        succeeded += 1
                        log.info(
                            "MatchExpirationJob (fallback) - expired match={}",
                            matchId
                        )
                    } else {
                        skipped += 1
                        log.debug(
                            "MatchExpirationJob (fallback) - skipped match={} because it was already expired",
                            matchId
                        )
                    }
                } catch (ex: Exception) {
                    log.error(
                        "MatchExpirationJob (fallback) - failed to expire match={}",
                        matchId,
                        ex
                    )
                    failed += 1
                }
            }
        }

        val summary =
            JobRunSummary(
                processed = processed,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            )
        log.logBatchComplete(
            jobName = "MatchExpirationJob",
            batchSize = batchSize,
            fetched = fetched,
            backlogRemaining = backlogRemaining
        )
        log.logJobSummary(
            jobName = "MatchExpirationJob",
            summary = summary,
            startedAt = startedAt,
            schedulerMetrics = schedulerMetrics,
            backlogRemaining = backlogRemaining
        )
        return summary
    }
}
