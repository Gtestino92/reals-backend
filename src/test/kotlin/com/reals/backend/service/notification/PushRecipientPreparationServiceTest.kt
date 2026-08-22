package com.reals.backend.service.notification

import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.NotificationPreferenceService
import com.reals.backend.service.notification.sender.PushNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.util.UUID

class PushRecipientPreparationServiceTest {

    private val deliveryPersistenceService = Mockito.mock(PushNotificationDeliveryPersistenceService::class.java)
    private val notificationPreferenceService = Mockito.mock(NotificationPreferenceService::class.java)
    private val service =
        PushRecipientPreparationService(
            deliveryPersistenceService = deliveryPersistenceService,
            notificationPreferenceService = notificationPreferenceService
        )

    @Test
    fun `preference rejection is persisted before token lookup`() {
        val userId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
        var notificationFactoryInvoked = false

        Mockito.`when`(
            deliveryPersistenceService.deliveryExists(
                userId = userId,
                notificationType = PushNotificationType.MATCH_FOUND,
                aggregateId = aggregateId
            )
        ).thenReturn(false)
        Mockito.`when`(
            notificationPreferenceService.isAllowed(
                userId = userId,
                notificationType = PushNotificationType.MATCH_FOUND
            )
        ).thenReturn(false)

        val prepared =
            service.prepareRecipient(
                userId = userId,
                notificationType = PushNotificationType.MATCH_FOUND,
                aggregateId = aggregateId,
                now = now
            ) {
                notificationFactoryInvoked = true
                PushNotification(
                    title = "title",
                    body = "body",
                    data = emptyMap()
                )
            }

        assertEquals(true, prepared.skipped)
        assertNull(prepared.command)
        assertFalse(notificationFactoryInvoked)
        Mockito.verify(deliveryPersistenceService).saveSkippedUserPreferenceInCurrentTransaction(
            userId = userId,
            notificationType = PushNotificationType.MATCH_FOUND,
            aggregateId = aggregateId,
            now = now
        )
        Mockito.verify(deliveryPersistenceService, Mockito.never()).activeTokenSnapshots(userId)
    }
}
