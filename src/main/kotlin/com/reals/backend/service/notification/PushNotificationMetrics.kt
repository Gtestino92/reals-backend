package com.reals.backend.service.notification

import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

interface PushNotificationMetrics {
    fun recordProviderCommand(
        notificationType: PushNotificationType,
        outcome: PreparedPushCommandOutcome
    )

    fun recordDelivery(
        notificationType: PushNotificationType,
        status: PushDeliveryStatus,
        persistenceOutcome: DeliveryPersistenceOutcome
    )

    fun recordPersistenceFailure(
        notificationType: PushNotificationType,
        phase: String
    )

    fun recordInvalidTokensDisabled(
        notificationType: PushNotificationType,
        count: Int
    )

    companion object {
        fun noop(): PushNotificationMetrics = NoopPushNotificationMetrics
    }
}

private object NoopPushNotificationMetrics : PushNotificationMetrics {
    override fun recordProviderCommand(
        notificationType: PushNotificationType,
        outcome: PreparedPushCommandOutcome
    ) = Unit

    override fun recordDelivery(
        notificationType: PushNotificationType,
        status: PushDeliveryStatus,
        persistenceOutcome: DeliveryPersistenceOutcome
    ) = Unit

    override fun recordPersistenceFailure(
        notificationType: PushNotificationType,
        phase: String
    ) = Unit

    override fun recordInvalidTokensDisabled(
        notificationType: PushNotificationType,
        count: Int
    ) = Unit
}

@Component
class MicrometerPushNotificationMetrics(
    private val meterRegistry: MeterRegistry
) : PushNotificationMetrics {

    override fun recordProviderCommand(
        notificationType: PushNotificationType,
        outcome: PreparedPushCommandOutcome
    ) {
        Counter.builder(PROVIDER_COMMANDS)
            .tag(TYPE, notificationType.metricTag())
            .tag(OUTCOME, outcome.metricTag())
            .register(meterRegistry)
            .increment()
    }

    override fun recordDelivery(
        notificationType: PushNotificationType,
        status: PushDeliveryStatus,
        persistenceOutcome: DeliveryPersistenceOutcome
    ) {
        Counter.builder(DELIVERIES)
            .tag(TYPE, notificationType.metricTag())
            .tag(STATUS, status.metricTag())
            .tag(PERSISTENCE, persistenceOutcome.metricTag())
            .register(meterRegistry)
            .increment()
    }

    override fun recordPersistenceFailure(
        notificationType: PushNotificationType,
        phase: String
    ) {
        Counter.builder(PERSISTENCE_FAILURES)
            .tag(TYPE, notificationType.metricTag())
            .tag(PHASE, phase)
            .register(meterRegistry)
            .increment()
    }

    override fun recordInvalidTokensDisabled(
        notificationType: PushNotificationType,
        count: Int
    ) {
        if (count <= 0) {
            return
        }

        Counter.builder(INVALID_TOKENS_DISABLED)
            .tag(TYPE, notificationType.metricTag())
            .register(meterRegistry)
            .increment(count.toDouble())
    }

    companion object {
        const val PROVIDER_COMMANDS = "reals.push.provider.commands"
        const val DELIVERIES = "reals.push.deliveries"
        const val PERSISTENCE_FAILURES = "reals.push.persistence.failures"
        const val INVALID_TOKENS_DISABLED = "reals.push.invalid_tokens_disabled"

        private const val TYPE = "type"
        private const val OUTCOME = "outcome"
        private const val STATUS = "status"
        private const val PERSISTENCE = "persistence"
        private const val PHASE = "phase"
    }
}

private fun PushNotificationType.metricTag(): String =
    name.lowercase()

private fun PushDeliveryStatus.metricTag(): String =
    name.lowercase()

private fun PreparedPushCommandOutcome.metricTag(): String =
    name.lowercase()

private fun DeliveryPersistenceOutcome.metricTag(): String =
    name.lowercase()
