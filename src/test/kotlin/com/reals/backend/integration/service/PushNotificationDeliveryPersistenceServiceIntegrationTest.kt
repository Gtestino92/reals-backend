package com.reals.backend.integration.service

import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.PushPlatform
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.notification.DeliveryPersistenceOutcome
import com.reals.backend.service.notification.PreparedPushCommand
import com.reals.backend.service.notification.PushNotificationDeliveryPersistenceService
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationToken
import com.reals.backend.service.notification.sender.PushSendResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PushNotificationDeliveryPersistenceServiceIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var deliveryPersistenceService: PushNotificationDeliveryPersistenceService

    @BeforeEach
    fun cleanPushState() {
        pushNotificationDeliveryRepository.deleteAll()
        pushDeviceTokenRepository.deleteAll()
    }

    @Test
    fun `persisting invalid push tokens disables only the reported token for a multi-device user`() {
        val user = userService.createUser("push-persistence-${UUID.randomUUID()}@example.com")
        val invalidToken =
            pushDeviceTokenService.registerToken(
                userId = user.id,
                token = "invalid-token-a-${UUID.randomUUID()}",
                platform = PushPlatform.ANDROID
            )
        val validToken =
            pushDeviceTokenService.registerToken(
                userId = user.id,
                token = "valid-token-b-${UUID.randomUUID()}",
                platform = PushPlatform.ANDROID
            )
        assertEquals(2, pushDeviceTokenRepository.findByUserIdAndEnabledTrue(user.id).size)

        val outcome =
            deliveryPersistenceService.persistSendResult(
                command = command(
                    userId = user.id,
                    tokens = listOf(
                        PushNotificationToken(id = invalidToken.id, token = invalidToken.token),
                        PushNotificationToken(id = validToken.id, token = validToken.token)
                    )
                ),
                sendResult = PushSendResult(
                    sent = true,
                    providerMessageIds = listOf("provider-message-id"),
                    invalidTokens = listOf(invalidToken.token),
                    errorMessage = "one token was invalid"
                ),
                now = NOW
            )

        assertEquals(DeliveryPersistenceOutcome.SAVED, outcome)
        assertFalse(pushDeviceTokenRepository.findByToken(invalidToken.token)?.enabled == true)
        assertTrue(pushDeviceTokenRepository.findByToken(validToken.token)?.enabled == true)
        assertEquals(listOf(validToken.id), pushDeviceTokenRepository.findByUserIdAndEnabledTrue(user.id).map { it.id })
        val delivery = pushNotificationDeliveryRepository
            .findByUserIdAndNotificationTypeAndAggregateId(
                userId = user.id,
                notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
                aggregateId = AGGREGATE_ID
            )
            ?: error("Expected persisted push delivery")
        assertEquals(PushDeliveryStatus.SENT, delivery.status)
    }

    private fun command(
        userId: UUID,
        tokens: List<PushNotificationToken>
    ): PreparedPushCommand =
        PreparedPushCommand(
            userId = userId,
            notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
            aggregateId = AGGREGATE_ID,
            tokens = tokens,
            notification = PushNotification(
                title = "Title",
                body = "Body",
                data = mapOf("type" to PushNotificationType.VISUAL_REVIEW_REMINDER.name)
            ),
            preparedAt = NOW
        )

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-07-17T12:00:00Z")
        val AGGREGATE_ID: UUID = UUID.fromString("8f7e0b07-73f2-4d2d-8b85-62236540f76f")
    }
}
