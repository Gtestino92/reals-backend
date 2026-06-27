package com.reals.backend.scheduler

import com.reals.backend.domain.ConnectionState
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.service.SchedulingService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Closes scheduling negotiations that have exceeded their deadline.
 *
 * Runs every 15 minutes. Config key:
 * scheduler.scheduling-timeout-job.fixed-delay
 */
@Component
class SchedulingNegotiationTimeoutJob(
    private val connectionRepository: ConnectionRepository,
    private val schedulingService: SchedulingService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.scheduling-timeout-job.fixed-delay}")
    @SchedulerLock(
        name = "SchedulingNegotiationTimeoutJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT5M"
    )
    fun run() {
        processTimedOutNegotiations()
    }

    internal fun processTimedOutNegotiations(): JobRunSummary {
        val startedAt = System.nanoTime()
        log.debug("SchedulingNegotiationTimeoutJob triggered")

        val timedOut =
            connectionRepository.findByStateAndSchedulingExpiresAtBefore(
                state = ConnectionState.SCHEDULING_PHASE,
                before = OffsetDateTime.now()
            )

        var succeeded = 0
        var skipped = 0
        var failed = 0

        timedOut.forEach { connection ->
            try {
                val changed = schedulingService.expireNegotiation(
                    connectionId = connection.id
                )
                if (changed) {
                    succeeded += 1
                    log.info(
                        "SchedulingNegotiationTimeoutJob - closed connection={}",
                        connection.id
                    )
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                log.error(
                    "SchedulingNegotiationTimeoutJob - failed for connection={}",
                    connection.id,
                    ex
                )
                failed += 1
            }
        }

        val summary = JobRunSummary(
            processed = timedOut.size,
            succeeded = succeeded,
            skipped = skipped,
            failed = failed
        )

        log.logJobSummary(
            jobName = "SchedulingNegotiationTimeoutJob",
            summary = summary,
            startedAt = startedAt
        )

        return summary
    }
}
