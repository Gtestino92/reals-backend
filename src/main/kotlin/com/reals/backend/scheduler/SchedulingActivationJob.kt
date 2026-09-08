package com.reals.backend.scheduler

import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.service.notification.SchedulingAvailableNotificationService
import com.reals.backend.service.SchedulingService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Enables scheduling for connections whose deferred availability time has arrived.
 */
@Component
class SchedulingActivationJob(
    private val connectionRepository: ConnectionRepository,
    private val schedulingService: SchedulingService,
    private val schedulingAvailableNotificationService: SchedulingAvailableNotificationService,
    @param:Value("\${scheduler.scheduling-activation-job.batch-size:100}")
    private val batchSize: Int = 100,
    private val schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.scheduling-activation-job.fixed-delay}")
    @SchedulerLock(
        name = "SchedulingActivationJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT5M"
    )
    fun run() {
        processSchedulingActivations()
    }

    internal fun processSchedulingActivations(): JobRunSummary {
        require(batchSize > 0) { "scheduler.scheduling-activation-job.batch-size must be positive" }
        val startedAt = System.nanoTime()
        log.debug("SchedulingActivationJob triggered")

        val now = OffsetDateTime.now()
        val batch =
            boundedSchedulerBatch(
                fetchedCandidates = connectionRepository.findSchedulingActivationDueIds(
                    now = now,
                    pageable = PageRequest.of(0, batchSize + 1)
                ),
                batchSize = batchSize
            )

        var succeeded = 0
        var skipped = 0
        var failed = 0
        val activatedConnectionIds = mutableListOf<UUID>()

        batch.items.forEach { connectionId ->
            try {
                schedulingService.activateSchedulingAndInitializeNegotiation(
                    connectionId = connectionId
                )

                activatedConnectionIds += connectionId
                succeeded += 1
                log.info(
                    "SchedulingActivationJob - activated scheduling for connection={}",
                    connectionId
                )
            } catch (ex: IllegalStateException) {
                skipped += 1
                log.warn(
                    "SchedulingActivationJob - skipped connection={} reason={}",
                    connectionId,
                    ex.message
                )
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SchedulingActivationJob - failed for connection={}",
                    connectionId,
                    ex
                )
            }
        }

        notifySchedulingAvailable(activatedConnectionIds)

        val summary =
            JobRunSummary(
                processed = batch.items.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            )
        log.logBatchComplete(
            jobName = "SchedulingActivationJob",
            batchSize = batchSize,
            fetched = batch.fetched,
            backlogRemaining = batch.backlogRemaining
        )
        log.logJobSummary(
            jobName = "SchedulingActivationJob",
            summary = summary,
            startedAt = startedAt,
            schedulerMetrics = schedulerMetrics,
            backlogRemaining = batch.backlogRemaining
        )
        return summary
    }

    private fun notifySchedulingAvailable(connectionIds: Collection<UUID>) {
        if (connectionIds.isEmpty()) {
            return
        }

        try {
            schedulingAvailableNotificationService.notifySchedulingAvailable(connectionIds)
        } catch (ex: Exception) {
            log.warn(
                "SchedulingActivationJob - scheduling available notification failed for connections={}",
                connectionIds,
                ex
            )
        }
    }
}
