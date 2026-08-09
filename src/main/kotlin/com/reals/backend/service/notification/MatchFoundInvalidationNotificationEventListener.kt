package com.reals.backend.service.notification

import com.reals.backend.service.FirstChatTerminatedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class MatchFoundInvalidationNotificationEventListener(
    private val matchFoundInvalidationNotificationService: MatchFoundInvalidationNotificationService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onFirstChatTerminated(event: FirstChatTerminatedEvent) {
        try {
            matchFoundInvalidationNotificationService.notifyMatchFoundInvalidated(event)
        } catch (ex: Exception) {
            log.warn(
                "Match found invalidation listener failed for match={} chat={}",
                event.matchId,
                event.chatId,
                ex
            )
        }
    }
}
