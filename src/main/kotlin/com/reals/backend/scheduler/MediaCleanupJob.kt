package com.reals.backend.scheduler

import com.reals.backend.config.s3.MediaCleanupProperties
import com.reals.backend.domain.MediaCleanupTaskStatus
import com.reals.backend.repository.MediaCleanupTaskRepository
import com.reals.backend.service.MediaCleanupProcessResult
import com.reals.backend.service.MediaCleanupProcessor
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class MediaCleanupJob(
    private val repository: MediaCleanupTaskRepository,
    private val processor: MediaCleanupProcessor,
    private val properties: MediaCleanupProperties,
    private val schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.media-cleanup-job.fixed-delay}")
    @SchedulerLock(name = "MediaCleanupJob", lockAtLeastFor = "PT30S", lockAtMostFor = "PT15M")
    fun run() {
        processMediaCleanup()
    }

    fun runNowForDev() {
        processMediaCleanup()
    }

    internal fun processMediaCleanup(): JobRunSummary {
        val startedAt = System.nanoTime()
        val now = OffsetDateTime.now()
        val taskIds = repository.findEligibleTaskIds(
            now = now,
            pendingStatus = MediaCleanupTaskStatus.PENDING,
            processingStatus = MediaCleanupTaskStatus.PROCESSING,
            pageable = PageRequest.of(0, properties.batchSize + 1)
        )
        val batch = boundedSchedulerBatch(taskIds, properties.batchSize)

        var succeeded = 0
        var skipped = 0
        var failed = 0

        batch.items.forEach { taskId ->
            try {
                when (processor.processTask(taskId = taskId, now = now)) {
                    MediaCleanupProcessResult.SUCCEEDED -> succeeded += 1
                    MediaCleanupProcessResult.SKIPPED -> skipped += 1
                    MediaCleanupProcessResult.FAILED -> failed += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error("MediaCleanupJob - failed to process cleanup task={}", taskId, ex)
            }
        }

        val summary = JobRunSummary(
            processed = batch.items.size,
            succeeded = succeeded,
            skipped = skipped,
            failed = failed
        )
        log.logBatchComplete(
            jobName = "MediaCleanupJob",
            batchSize = properties.batchSize,
            fetched = batch.fetched,
            backlogRemaining = batch.backlogRemaining
        )
        log.logJobSummary(
            jobName = "MediaCleanupJob",
            summary = summary,
            startedAt = startedAt,
            schedulerMetrics = schedulerMetrics,
            backlogRemaining = batch.backlogRemaining
        )
        return summary
    }
}
