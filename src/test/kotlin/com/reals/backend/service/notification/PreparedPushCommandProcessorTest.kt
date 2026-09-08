package com.reals.backend.service.notification

import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationToken
import com.reals.backend.service.notification.sender.PushSendResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class PreparedPushCommandProcessorTest {

    private val dispatcher = RecordingProviderDispatcher()
    private val persistence = RecordingResultPersistence()
    private val metrics = RecordingPushNotificationMetrics()
    private val processor = PreparedPushCommandProcessor(dispatcher, persistence, metrics)

    @Test
    fun `successful provider result stays successful when result persistence fails`() {
        val command = command()
        val nextCommand = command()
        persistence.persistSendResultException = RuntimeException("database unavailable")
        dispatcher.results += { PushSendResult(sent = true, providerMessageIds = listOf("provider-id")) }
        dispatcher.results += { PushSendResult(sent = true, providerMessageIds = listOf("provider-id-2")) }

        val outcome = processor.process(command, NOW)
        persistence.persistSendResultException = null
        val nextOutcome = processor.process(nextCommand, NOW)

        assertEquals(PreparedPushCommandOutcome.SENT, outcome)
        assertEquals(PreparedPushCommandOutcome.SENT, nextOutcome)
        assertEquals(listOf(command, nextCommand), dispatcher.commands)
        assertEquals(1, persistence.persistSendResultCalls.count { it.command == command })
        assertEquals(0, persistence.persistFailureCalls.count { it.command == command })
        assertEquals(
            listOf(
                ProviderMetric(command.notificationType, PreparedPushCommandOutcome.SENT),
                ProviderMetric(nextCommand.notificationType, PreparedPushCommandOutcome.SENT)
            ),
            metrics.providerCommands
        )
        assertEquals(listOf(command.notificationType to "send_result"), metrics.persistenceFailures)
    }

    @Test
    fun `failed provider result remains failed when result persistence fails`() {
        val command = command()
        val nextCommand = command()
        persistence.persistSendResultException = RuntimeException("database unavailable")
        dispatcher.results += {
            PushSendResult(
                sent = false,
                errorMessage = "provider rejected delivery"
            )
        }
        dispatcher.results += { PushSendResult(sent = true, providerMessageIds = listOf("provider-id")) }

        val outcome = processor.process(command, NOW)
        persistence.persistSendResultException = null
        val nextOutcome = processor.process(nextCommand, NOW)

        assertEquals(PreparedPushCommandOutcome.NOT_SENT, outcome)
        assertEquals(PreparedPushCommandOutcome.SENT, nextOutcome)
        assertEquals(listOf(command, nextCommand), dispatcher.commands)
        assertEquals(1, persistence.persistSendResultCalls.count { it.command == command })
        assertEquals(0, persistence.persistFailureCalls.count { it.command == command })
    }

    @Test
    fun `provider transport exception records failure without result persistence or retry`() {
        val command = command()
        val nextCommand = command()
        dispatcher.results += { throw RuntimeException("provider unavailable") }
        dispatcher.results += { PushSendResult(sent = true, providerMessageIds = listOf("provider-id")) }

        val outcome = processor.process(command, NOW)
        val nextOutcome = processor.process(nextCommand, NOW)

        assertEquals(PreparedPushCommandOutcome.PROVIDER_EXCEPTION, outcome)
        assertEquals(PreparedPushCommandOutcome.SENT, nextOutcome)
        assertEquals(listOf(command, nextCommand), dispatcher.commands)
        assertEquals(0, persistence.persistSendResultCalls.count { it.command == command })
        assertEquals(1, persistence.persistFailureCalls.count { it.command == command })
        assertTrue(persistence.persistFailureCalls.single { it.command == command }.errorMessage.contains("provider unavailable"))
    }

    @Test
    fun `partial provider success persists invalid tokens without changing successful outcome`() {
        val command = command()
        val sendResult =
            PushSendResult(
                sent = true,
                providerMessageIds = listOf("provider-valid-token"),
                invalidTokens = listOf("invalid-token"),
                errorMessage = "one token was invalid"
            )
        dispatcher.results += { sendResult }

        val outcome = processor.process(command, NOW)

        assertEquals(PreparedPushCommandOutcome.SENT, outcome)
        assertEquals(listOf(command), dispatcher.commands)
        assertEquals(sendResult, persistence.persistSendResultCalls.single().sendResult)
        assertEquals(0, persistence.persistFailureCalls.size)
    }

    @Test
    fun `provider exception plus failure persistence exception does not escape or retry`() {
        val command = command()
        persistence.persistFailureException = RuntimeException("database unavailable")
        dispatcher.results += { throw RuntimeException("provider unavailable") }

        val outcome = processor.process(command, NOW)

        assertEquals(PreparedPushCommandOutcome.PROVIDER_EXCEPTION, outcome)
        assertEquals(listOf(command), dispatcher.commands)
        assertEquals(0, persistence.persistSendResultCalls.size)
        assertEquals(1, persistence.persistFailureCalls.size)
        assertEquals(
            listOf(ProviderMetric(command.notificationType, PreparedPushCommandOutcome.PROVIDER_EXCEPTION)),
            metrics.providerCommands
        )
        assertEquals(listOf(command.notificationType to "provider_failure"), metrics.persistenceFailures)
    }

    private data class ProviderMetric(
        val notificationType: PushNotificationType,
        val outcome: PreparedPushCommandOutcome
    )

    private fun command(): PreparedPushCommand =
        PreparedPushCommand(
            userId = UUID.randomUUID(),
            notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
            aggregateId = UUID.randomUUID(),
            tokens = listOf(PushNotificationToken(id = UUID.randomUUID(), token = "token-${UUID.randomUUID()}")),
            notification = PushNotification(
                title = "title",
                body = "body",
                data = mapOf("type" to PushNotificationType.VISUAL_REVIEW_REMINDER.name)
            ),
            preparedAt = NOW
        )

    private class RecordingProviderDispatcher : PushNotificationProviderDispatcher {
        val commands = mutableListOf<PreparedPushCommand>()
        val results = ArrayDeque<() -> PushSendResult>()

        override fun send(command: PreparedPushCommand): PushSendResult {
            commands += command
            return results.removeFirst().invoke()
        }
    }

    private class RecordingResultPersistence : PushNotificationResultPersistence {
        data class SendResultCall(
            val command: PreparedPushCommand,
            val sendResult: PushSendResult,
            val now: OffsetDateTime
        )

        data class FailureCall(
            val command: PreparedPushCommand,
            val errorMessage: String,
            val now: OffsetDateTime
        )

        val persistSendResultCalls = mutableListOf<SendResultCall>()
        val persistFailureCalls = mutableListOf<FailureCall>()
        var persistSendResultException: RuntimeException? = null
        var persistFailureException: RuntimeException? = null

        override fun persistSendResult(
            command: PreparedPushCommand,
            sendResult: PushSendResult,
            now: OffsetDateTime
        ): DeliveryPersistenceOutcome {
            persistSendResultCalls += SendResultCall(command, sendResult, now)
            persistSendResultException?.let { throw it }
            return DeliveryPersistenceOutcome.SAVED
        }

        override fun persistFailure(
            command: PreparedPushCommand,
            errorMessage: String,
            now: OffsetDateTime
        ): DeliveryPersistenceOutcome {
            persistFailureCalls += FailureCall(command, errorMessage, now)
            persistFailureException?.let { throw it }
            return DeliveryPersistenceOutcome.SAVED
        }
    }

    private class RecordingPushNotificationMetrics : PushNotificationMetrics {
        val providerCommands = mutableListOf<ProviderMetric>()
        val persistenceFailures = mutableListOf<Pair<PushNotificationType, String>>()

        override fun recordProviderCommand(
            notificationType: PushNotificationType,
            outcome: PreparedPushCommandOutcome
        ) {
            providerCommands += ProviderMetric(notificationType, outcome)
        }

        override fun recordDelivery(
            notificationType: PushNotificationType,
            status: com.reals.backend.domain.PushDeliveryStatus,
            persistenceOutcome: DeliveryPersistenceOutcome
        ) = Unit

        override fun recordPersistenceFailure(
            notificationType: PushNotificationType,
            phase: String
        ) {
            persistenceFailures += notificationType to phase
        }

        override fun recordInvalidTokensDisabled(
            notificationType: PushNotificationType,
            count: Int
        ) = Unit
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-07-17T12:00:00Z")
    }
}
