package com.reals.backend.scheduler

import com.reals.backend.service.PenaltyService
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBean(LockProvider::class)
class PenaltyExpirationJob(
    private val penaltyService: PenaltyService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString =
            "\${scheduler.penalty-expiration-job.fixed-delay}"
    )
    @SchedulerLock(name = "PenaltyExpirationJob", lockAtLeastFor = "PT30s", lockAtMostFor = "PT2M")
    fun run() {
        log.debug("Penalty Expiration Job triggered")
        penaltyService.expireOverduePenalties()
    }
}
