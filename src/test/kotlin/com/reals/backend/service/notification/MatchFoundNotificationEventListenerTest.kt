package com.reals.backend.service.notification

import com.reals.backend.service.MatchFoundEvent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

class MatchFoundNotificationEventListenerTest {

    @Test
    fun `match found listener runs after commit`() {
        val method =
            MatchFoundNotificationEventListener::class.java.getDeclaredMethod(
                "onMatchFound",
                MatchFoundEvent::class.java
            )

        val annotation = method.getAnnotation(TransactionalEventListener::class.java)

        assertNotNull(annotation)
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase)
    }

    @Test
    fun `match found listener swallows notification service failures`() {
        val service = Mockito.mock(MatchFoundNotificationService::class.java)
        val event =
            MatchFoundEvent(
                matchId = UUID.randomUUID(),
                chatId = UUID.randomUUID()
            )
        doThrow(RuntimeException("notification unavailable"))
            .`when`(service)
            .notifyMatchFound(event)

        val listener = MatchFoundNotificationEventListener(service)

        assertDoesNotThrow {
            listener.onMatchFound(event)
        }
    }
}
