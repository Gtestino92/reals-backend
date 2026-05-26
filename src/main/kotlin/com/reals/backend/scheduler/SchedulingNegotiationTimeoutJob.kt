package com.reals.backend.scheduler

import com.reals.backend.domain.ConnectionState
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.service.SchedulingService
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Closes scheduling negotiations that have exceeded their deadline.
 *
 * Runs every 15 minutes. Config key:
 * scheduler.scheduling-timeout-job.fixed-delay
 */
@Component
@ConditionalOnBean(LockProvider::class)
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
    @Transactional
    fun run() {
        log.debug("SchedulingNegotiationTimeoutJob triggered")

        val timedOut =
            connectionRepository.findByStateAndSchedulingExpiresAtBefore(
                state = ConnectionState.SCHEDULING_PHASE,
                before = OffsetDateTime.now()
            )

        if (timedOut.isEmpty()) {
            log.debug("SchedulingNegotiationTimeoutJob - no timed-out connections found")
            return
        }

        log.info(
            "SchedulingNegotiationTimeoutJob - found {} timed-out connection(s)",
            timedOut.size
        )

        timedOut.forEach { connection ->
            try {
                schedulingService.expireNegotiation(
                    connectionId = connection.id
                )

                log.info(
                    "SchedulingNegotiationTimeoutJob - closed connection={}",
                    connection.id
                )
            } catch (ex: Exception) {
                log.error(
                    "SchedulingNegotiationTimeoutJob - failed for connection={}: {}",
                    connection.id,
                    ex.message
                )
            }
        }
    }
}