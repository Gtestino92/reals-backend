package com.reals.backend.service.notification

import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.service.FirstChatTerminatedEvent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.OffsetDateTime
import java.util.UUID

class MatchFoundInvalidationNotificationEventListenerTest {

    @Test
    fun `match found invalidation listener runs after commit`() {
        val method =
            MatchFoundInvalidationNotificationEventListener::class.java.getDeclaredMethod(
                "onFirstChatTerminated",
                FirstChatTerminatedEvent::class.java
            )

        val annotation = method.getAnnotation(TransactionalEventListener::class.java)

        assertNotNull(annotation)
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase)
    }

    @Test
    fun `match found invalidation listener delegates event to notification service`() {
        val service = Mockito.mock(MatchFoundInvalidationNotificationService::class.java)
        val event = event()
        val listener = MatchFoundInvalidationNotificationEventListener(service)

        listener.onFirstChatTerminated(event)

        Mockito.verify(service).notifyMatchFoundInvalidated(
            Mockito.eq(event) ?: event,
            Mockito.any(OffsetDateTime::class.java) ?: OffsetDateTime.MIN
        )
    }

    @Test
    fun `match found invalidation listener swallows notification service failures`() {
        val service = Mockito.mock(MatchFoundInvalidationNotificationService::class.java)
        val event = event()
        doThrow(RuntimeException("notification unavailable"))
            .`when`(service)
            .notifyMatchFoundInvalidated(
                Mockito.eq(event) ?: event,
                Mockito.any(OffsetDateTime::class.java) ?: OffsetDateTime.MIN
            )

        val listener = MatchFoundInvalidationNotificationEventListener(service)

        assertDoesNotThrow {
            listener.onFirstChatTerminated(event)
        }
    }

    private fun event(): FirstChatTerminatedEvent =
        FirstChatTerminatedEvent(
            matchId = UUID.randomUUID(),
            chatId = UUID.randomUUID(),
            finalStatus = ChatStatus.CANCELLED,
            endedReason = ChatEndReason.UNILATERAL_CANCEL
        )
}
