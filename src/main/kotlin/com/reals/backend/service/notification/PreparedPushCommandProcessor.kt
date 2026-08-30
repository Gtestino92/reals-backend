package com.reals.backend.service.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

enum class PreparedPushCommandOutcome {
    SENT,
    NOT_SENT,
    PROVIDER_EXCEPTION
}

@Service
class PreparedPushCommandProcessor(
    private val notificationProviderDispatcher: PushNotificationProviderDispatcher,
    private val deliveryPersistenceService: PushNotificationResultPersistence,
    private val pushNotificationMetrics: PushNotificationMetrics = PushNotificationMetrics.noop()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun process(
        command: PreparedPushCommand,
        now: OffsetDateTime = OffsetDateTime.now()
    ): PreparedPushCommandOutcome {
        val sendResult =
            try {
                notificationProviderDispatcher.send(command)
            } catch (ex: Exception) {
                log.warn(
                    "Push provider transport failed for user {} type {} aggregate {}: {}",
                    command.userId,
                    command.notificationType,
                    command.aggregateId,
                    ex.message,
                    ex
                )
                persistProviderFailureBestEffort(command, ex, now)
                pushNotificationMetrics.recordProviderCommand(
                    notificationType = command.notificationType,
                    outcome = PreparedPushCommandOutcome.PROVIDER_EXCEPTION
                )
                return PreparedPushCommandOutcome.PROVIDER_EXCEPTION
            }

        try {
            deliveryPersistenceService.persistSendResult(
                command = command,
                sendResult = sendResult,
                now = now
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to persist push delivery result after provider returned for user {} type {} aggregate {}; provider delivery may already have occurred: {}",
                command.userId,
                command.notificationType,
                command.aggregateId,
                ex.message,
                ex
            )
            pushNotificationMetrics.recordPersistenceFailure(
                notificationType = command.notificationType,
                phase = RESULT_PHASE
            )
        }

        val outcome = if (sendResult.sent) {
            PreparedPushCommandOutcome.SENT
        } else {
            PreparedPushCommandOutcome.NOT_SENT
        }
        pushNotificationMetrics.recordProviderCommand(
            notificationType = command.notificationType,
            outcome = outcome
        )
        return outcome
    }

    private fun persistProviderFailureBestEffort(
        command: PreparedPushCommand,
        providerException: Exception,
        now: OffsetDateTime
    ) {
        try {
            deliveryPersistenceService.persistFailure(
                command = command,
                errorMessage = providerException.message ?: providerException.javaClass.simpleName,
                now = now
            )
        } catch (persistenceEx: Exception) {
            log.warn(
                "Failed to persist push provider transport failure for user {} type {} aggregate {}: {}",
                command.userId,
                command.notificationType,
                command.aggregateId,
                persistenceEx.message,
                persistenceEx
            )
            pushNotificationMetrics.recordPersistenceFailure(
                notificationType = command.notificationType,
                phase = PROVIDER_FAILURE_PHASE
            )
        }
    }

    private companion object {
        const val RESULT_PHASE = "send_result"
        const val PROVIDER_FAILURE_PHASE = "provider_failure"
    }
}
