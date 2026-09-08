package com.reals.backend.service.notification

import com.reals.backend.service.SchedulingConfirmedEvent
import com.reals.backend.service.SchedulingProposalsReceivedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class SchedulingNotificationEventListener(
    private val proposalsReceivedNotificationService: SchedulingProposalsReceivedNotificationService,
    private val confirmedNotificationService: SchedulingConfirmedNotificationService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onProposalsReceived(event: SchedulingProposalsReceivedEvent) {
        try {
            proposalsReceivedNotificationService.notifyProposalsReceived(event)
        } catch (ex: Exception) {
            log.warn(
                "Scheduling proposals received listener failed for connection={} recipient={} round={}",
                event.connectionId,
                event.recipientUserId,
                event.roundNumber,
                ex
            )
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onSchedulingConfirmed(event: SchedulingConfirmedEvent) {
        try {
            confirmedNotificationService.notifySchedulingConfirmed(event)
        } catch (ex: Exception) {
            log.warn(
                "Scheduling confirmed listener failed for connection={} triggeringUser={}",
                event.connectionId,
                event.triggeringUserId,
                ex
            )
        }
    }
}
