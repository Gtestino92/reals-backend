package com.reals.backend.service.notification

import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.PushDeviceTokenRepository
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationToken
import com.reals.backend.service.notification.sender.PushSendResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

class PushNotificationDeliveryPersistenceServiceTest {

    @Test
    fun `duplicate delivery race still disables invalid tokens after failed insert`() {
        val deliveryRepository = Mockito.mock(PushNotificationDeliveryRepository::class.java)
        val tokenRepository = Mockito.mock(PushDeviceTokenRepository::class.java)
        val service =
            PushNotificationDeliveryPersistenceService(
                deliveryRepository = deliveryRepository,
                tokenRepository = tokenRepository,
                transactionTemplate = TransactionTemplate(NoOpTransactionManager())
            )
        val command = command()
        Mockito.`when`(
            deliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                command.userId,
                command.notificationType,
                command.aggregateId
            )
        ).thenReturn(null)
        Mockito.`when`(deliveryRepository.saveAndFlush(Mockito.any(PushNotificationDelivery::class.java)))
            .thenThrow(DataIntegrityViolationException("duplicate delivery"))
        Mockito.`when`(tokenRepository.disableByToken(eqValue("invalid-token-a"), anyOffsetDateTime()))
            .thenReturn(1)

        val outcome =
            service.persistSendResult(
                command = command,
                sendResult = PushSendResult(
                    sent = false,
                    invalidTokens = listOf("invalid-token-a"),
                    errorMessage = "registration token is not registered"
                ),
                now = NOW
            )

        assertEquals(DeliveryPersistenceOutcome.DUPLICATE, outcome)
        Mockito.verify(tokenRepository, Mockito.times(2)).disableByToken(
            eqValue("invalid-token-a"),
            anyOffsetDateTime()
        )
    }

    private fun command(): PreparedPushCommand =
        PreparedPushCommand(
            userId = UUID.randomUUID(),
            notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
            aggregateId = UUID.randomUUID(),
            tokens = listOf(PushNotificationToken(id = UUID.randomUUID(), token = "invalid-token-a")),
            notification = PushNotification(
                title = "Title",
                body = "Body",
                data = mapOf("type" to PushNotificationType.VISUAL_REVIEW_REMINDER.name)
            ),
            preparedAt = NOW
        )

    private fun anyOffsetDateTime(): OffsetDateTime {
        Mockito.any(OffsetDateTime::class.java)
        return NOW
    }

    private fun <T> eqValue(value: T): T {
        Mockito.eq(value)
        return value
    }

    private class NoOpTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit

        override fun doCommit(status: DefaultTransactionStatus) = Unit

        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-07-17T12:00:00Z")
    }
}
