package com.reals.backend.service.notification

import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PushNotificationMetricsTest {

    @Test
    fun `push metrics use low cardinality operational tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerPushNotificationMetrics(registry)

        metrics.recordProviderCommand(
            notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
            outcome = PreparedPushCommandOutcome.PROVIDER_EXCEPTION
        )
        metrics.recordDelivery(
            notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
            status = PushDeliveryStatus.FAILED,
            persistenceOutcome = DeliveryPersistenceOutcome.SAVED
        )
        metrics.recordPersistenceFailure(
            notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
            phase = "send_result"
        )
        metrics.recordInvalidTokensDisabled(
            notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
            count = 2
        )

        assertEquals(
            1.0,
            registry.get(MicrometerPushNotificationMetrics.PROVIDER_COMMANDS)
                .tag("type", "visual_review_reminder")
                .tag("outcome", "provider_exception")
                .counter()
                .count()
        )
        assertEquals(
            1.0,
            registry.get(MicrometerPushNotificationMetrics.DELIVERIES)
                .tag("type", "visual_review_reminder")
                .tag("status", "failed")
                .tag("persistence", "saved")
                .counter()
                .count()
        )
        assertEquals(
            1.0,
            registry.get(MicrometerPushNotificationMetrics.PERSISTENCE_FAILURES)
                .tag("type", "visual_review_reminder")
                .tag("phase", "send_result")
                .counter()
                .count()
        )
        assertEquals(
            2.0,
            registry.get(MicrometerPushNotificationMetrics.INVALID_TOKENS_DISABLED)
                .tag("type", "visual_review_reminder")
                .counter()
                .count()
        )
    }
}
