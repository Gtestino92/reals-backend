package com.reals.backend.scheduler

import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.VisualReviewService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
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
    private val visualReviewService: VisualReviewService,
    @param:Value("\${scheduler.visual-phase-expiration-job.batch-size:100}")
    private val batchSize: Int = 100,
    private val schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.visual-phase-expiration-job.fixed-delay}")
    @SchedulerLock(
        name = "VisualPhaseExpirationJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT5M"
    )
    fun run() {
        processVisualPhaseExpirations()
    }

    internal fun processVisualPhaseExpirations(): JobRunSummary {
        require(batchSize > 0) { "scheduler.visual-phase-expiration-job.batch-size must be positive" }
        val startedAt = System.nanoTime()
        log.debug("VisualPhaseExpirationJob triggered")

        val now = OffsetDateTime.now()
        val batch =
            boundedSchedulerBatch(
                fetchedCandidates = visualReviewRepository.findExpiredMatchIds(
                    expiresAt = now,
                    pageable = PageRequest.of(0, batchSize + 1)
                ),
                batchSize = batchSize
            )

        var succeeded = 0
        var skipped = 0
        var failed = 0

        batch.items.forEach { matchId ->
            try {
                val changed = visualReviewService.expireVisualReview(matchId)
                if (changed) {
                    succeeded += 1
                    log.info(
                        "VisualPhaseExpirationJob - expired match={}",
                        matchId
                    )
                } else {
                    skipped += 1
                    log.debug(
                        "VisualPhaseExpirationJob - skipped match={} because it was already expired or not due",
                        matchId
                    )
                }
            } catch (ex: Exception) {
                log.error(
                    "VisualPhaseExpirationJob - failed to expire match={}",
                    matchId,
                    ex
                )
                failed += 1
            }
        }

        val summary =
            JobRunSummary(
                processed = batch.items.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            )
        log.logBatchComplete(
            jobName = "VisualPhaseExpirationJob",
            batchSize = batchSize,
            fetched = batch.fetched,
            backlogRemaining = batch.backlogRemaining
        )
        log.logJobSummary(
            jobName = "VisualPhaseExpirationJob",
            summary = summary,
            startedAt = startedAt,
            schedulerMetrics = schedulerMetrics,
            backlogRemaining = batch.backlogRemaining
        )
        return summary
    }
}
