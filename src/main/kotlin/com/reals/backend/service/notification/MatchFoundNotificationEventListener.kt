package com.reals.backend.service.notification

import com.reals.backend.service.MatchFoundEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class MatchFoundNotificationEventListener(
    private val matchFoundNotificationService: MatchFoundNotificationService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMatchFound(event: MatchFoundEvent) {
        try {
            matchFoundNotificationService.notifyMatchFound(event)
        } catch (ex: Exception) {
            log.warn(
                "Match found notification listener failed for match={} chat={}",
                event.matchId,
                event.chatId,
                ex
            )
        }
    }
}
