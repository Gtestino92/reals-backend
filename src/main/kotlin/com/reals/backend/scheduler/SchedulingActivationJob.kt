package com.reals.backend.scheduler

import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.service.SchedulingService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Enables scheduling for connections whose deferred availability time has arrived.
 */
@Component
class SchedulingActivationJob(
    private val connectionRepository: ConnectionRepository,
    private val schedulingService: SchedulingService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.scheduling-activation-job.fixed-delay}")
    @SchedulerLock(
        name = "SchedulingActivationJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT5M"
    )
    fun run() {
        val startedAt = System.nanoTime()
        log.debug("SchedulingActivationJob triggered")

        val due =
            connectionRepository.findSchedulingActivationDue(
                now = OffsetDateTime.now()
            )

        var succeeded = 0
        var skipped = 0
        var failed = 0

        due.forEach { connection ->
            try {
                schedulingService.activateSchedulingAndInitializeNegotiation(
                    connectionId = connection.id
                )

                succeeded += 1
                log.info(
                    "SchedulingActivationJob - activated scheduling for connection={}",
                    connection.id
                )
            } catch (ex: IllegalStateException) {
                skipped += 1
                log.warn(
                    "SchedulingActivationJob - skipped connection={} reason={}",
                    connection.id,
                    ex.message
                )
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SchedulingActivationJob - failed for connection={}",
                    connection.id,
                    ex
                )
            }
        }

        log.logJobSummary(
            jobName = "SchedulingActivationJob",
            summary = JobRunSummary(
                processed = due.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            ),
            startedAt = startedAt
        )
    }
}
