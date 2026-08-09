package com.reals.backend.integration.service

import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.PushPlatform
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.FirstChatTerminatedEvent
import com.reals.backend.service.notification.MatchFoundInvalidationNotificationService
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationAndroidPriority
import com.reals.backend.service.notification.sender.PushNotificationSender
import com.reals.backend.service.notification.sender.PushNotificationToken
import com.reals.backend.service.notification.sender.PushSendResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Import(MatchFoundInvalidationNotificationIntegrationTest.PushSenderTestConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatchFoundInvalidationNotificationIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var pushSender: RecordingPushNotificationSender

    @Autowired
    private lateinit var notificationService: MatchFoundInvalidationNotificationService

    @BeforeEach
    fun resetPushSender() {
        pushSender.reset()
    }

    @Test
    fun `match found invalidation sends data only high priority control message to both participants`() {
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
        val timeoutAt = now.plusMinutes(5)
        val setup = createTerminalFirstChat("match-found-invalidation-contract", now, timeoutAt)
        pushDeviceTokenService.registerToken(setup.userAId, "invalidation-token-a-1", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userAId, "invalidation-token-a-2", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "invalidation-token-b-1", PushPlatform.ANDROID)

        notificationService.notifyMatchFoundInvalidated(eventFor(setup), now)

        assertEquals(2, pushSender.attempts.size)
        assertEquals(
            listOf("invalidation-token-a-1", "invalidation-token-a-2", "invalidation-token-b-1"),
            pushSender.attempts.flatMap { it.tokens }.sorted()
        )
        assertTrue(pushSender.attempts.none { it.transactionActive })
        pushSender.attempts.forEach { attempt ->
            assertEquals(setOf("type", "matchId"), attempt.notification.data.keys)
            assertEquals(PushNotificationType.MATCH_FOUND_INVALIDATED.name, attempt.notification.data["type"])
            assertEquals(setup.matchId.toString(), attempt.notification.data["matchId"])
            assertEquals(Duration.between(now, timeoutAt).toMillis(), attempt.notification.androidTtlMillis)
            assertFalse(attempt.notification.includeNotificationPayload)
            assertEquals(PushNotificationAndroidPriority.HIGH, attempt.notification.androidPriority)
            assertEquals(null, attempt.notification.androidNotificationTag)
        }
        val deliveries = invalidationDeliveriesFor(setup.matchId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SENT })

        pushSender.reset()

        notificationService.notifyMatchFoundInvalidated(eventFor(setup), now)

        assertEquals(0, pushSender.attempts.size)
        assertEquals(2, invalidationDeliveriesFor(setup.matchId).size)
    }

    @Test
    fun `match found invalidation skips existing delivery and records no active token skip`() {
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
        val setup = createTerminalFirstChat("match-found-invalidation-skips", now, now.plusMinutes(5))
        pushNotificationDeliveryRepository.saveAndFlush(
            PushNotificationDelivery(
                userId = setup.userAId,
                notificationType = PushNotificationType.MATCH_FOUND_INVALIDATED,
                aggregateId = setup.matchId,
                sentAt = now.minusSeconds(1),
                status = PushDeliveryStatus.SENT,
                providerMessageId = "existing-provider-id",
                createdAt = now.minusSeconds(1),
                updatedAt = now.minusSeconds(1)
            )
        )

        notificationService.notifyMatchFoundInvalidated(eventFor(setup), now)

        val deliveries = invalidationDeliveriesFor(setup.matchId)
        assertEquals(0, pushSender.attempts.size)
        assertEquals(2, deliveries.size)
        assertEquals(PushDeliveryStatus.SENT, deliveries.first { it.userId == setup.userAId }.status)
        assertEquals(PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN, deliveries.first { it.userId == setup.userBId }.status)
    }

    @Test
    fun `match found invalidation processes duplicate participant id once`() {
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
        val setup = createTerminalFirstChat("match-found-invalidation-duplicate", now, now.plusMinutes(5))
        val match = matchRepository.findById(setup.matchId).orElseThrow()
        match.userBId = setup.userAId
        matchRepository.saveAndFlush(match)
        pushDeviceTokenService.registerToken(setup.userAId, "invalidation-duplicate-token", PushPlatform.ANDROID)

        notificationService.notifyMatchFoundInvalidated(eventFor(setup), now)

        assertEquals(1, pushSender.attempts.size)
        assertEquals(listOf("invalidation-duplicate-token"), pushSender.attempts.single().tokens)
        assertEquals(1, invalidationDeliveriesFor(setup.matchId).size)
    }

    @Test
    fun `match found invalidation ignores defensive mismatch active non-first-chat and stale timeout cases`() {
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")

        listOf(
            "wrong chat match" to {
                val setup = createTerminalFirstChat("match-found-invalidation-wrong-match", now, now.plusMinutes(5))
                val other = createTerminalFirstChat("match-found-invalidation-other-match", now, now.plusMinutes(5))
                setup to eventFor(other).copy(matchId = setup.matchId)
            },
            "still active first chat" to {
                val setup = createMatchWithFirstChat("match-found-invalidation-active")
                updateChatWindow(setup.firstChatId, now.plusMinutes(5))
                setup to eventFor(setup)
            },
            "non first chat" to {
                val setup = createTerminalFirstChat("match-found-invalidation-non-first", now, now.plusMinutes(5))
                val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
                chat.chatType = ChatType.SECOND_CHAT
                chatRepository.saveAndFlush(chat)
                setup to eventFor(setup)
            },
            "timeout exactly now" to {
                val setup = createTerminalFirstChat("match-found-invalidation-timeout-equal", now, now)
                setup to eventFor(setup)
            },
            "timeout before now" to {
                val setup = createTerminalFirstChat("match-found-invalidation-timeout-before", now, now.minusNanos(1))
                setup to eventFor(setup)
            }
        ).forEach { (scenario, prepare) ->
            pushSender.reset()
            val (setup, event) = prepare()
            pushDeviceTokenService.registerToken(setup.userAId, "$scenario-token-a", PushPlatform.ANDROID)
            pushDeviceTokenService.registerToken(setup.userBId, "$scenario-token-b", PushPlatform.ANDROID)

            notificationService.notifyMatchFoundInvalidated(event, now)

            assertEquals(0, pushSender.attempts.size, scenario)
            assertEquals(0, invalidationDeliveriesFor(setup.matchId).size, scenario)
        }
    }

    @Test
    fun `match found invalidation provider failure does not change terminal chat state`() {
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
        val setup = createTerminalFirstChat("match-found-invalidation-failure", now, now.plusMinutes(5))
        pushDeviceTokenService.registerToken(setup.userAId, "invalidation-failure-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "invalidation-failure-token-b", PushPlatform.ANDROID)
        pushSender.throwOnSend = RuntimeException("provider unavailable")

        notificationService.notifyMatchFoundInvalidated(eventFor(setup), now)

        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(setup.firstChatId).orElseThrow().status)
        assertEquals(ChatEndReason.UNILATERAL_CANCEL, chatRepository.findById(setup.firstChatId).orElseThrow().endedReason)
        val deliveries = invalidationDeliveriesFor(setup.matchId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.FAILED })
    }

    private fun createTerminalFirstChat(
        emailPrefix: String,
        now: OffsetDateTime,
        timeoutAt: OffsetDateTime
    ): MatchFixture {
        val setup = createMatchWithFirstChat(emailPrefix)
        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        chat.timeoutAt = timeoutAt
        chat.status = ChatStatus.CANCELLED
        chat.endedAt = now.minusSeconds(1)
        chat.endedReason = ChatEndReason.UNILATERAL_CANCEL
        chatRepository.saveAndFlush(chat)
        return setup
    }

    private fun updateChatWindow(
        chatId: UUID,
        timeoutAt: OffsetDateTime
    ) {
        val chat = chatRepository.findById(chatId).orElseThrow()
        chat.timeoutAt = timeoutAt
        chatRepository.saveAndFlush(chat)
    }

    private fun eventFor(setup: MatchFixture): FirstChatTerminatedEvent =
        FirstChatTerminatedEvent(
            matchId = setup.matchId,
            chatId = setup.firstChatId,
            finalStatus = ChatStatus.CANCELLED,
            endedReason = ChatEndReason.UNILATERAL_CANCEL
        )

    private fun invalidationDeliveriesFor(matchId: UUID) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.MATCH_FOUND_INVALIDATED,
            aggregateId = matchId
        )

    @TestConfiguration
    class PushSenderTestConfig {
        @Bean
        @Primary
        fun recordingPushNotificationSender(): RecordingPushNotificationSender =
            RecordingPushNotificationSender()
    }

    class RecordingPushNotificationSender : PushNotificationSender {
        data class Attempt(
            val tokens: List<String>,
            val notification: PushNotification,
            val transactionActive: Boolean
        )

        val attempts: MutableList<Attempt> = mutableListOf()
        var throwOnSend: RuntimeException? = null

        override fun sendToTokens(
            tokens: List<PushNotificationToken>,
            notification: PushNotification
        ): PushSendResult {
            attempts += Attempt(
                tokens = tokens.map { it.token },
                notification = notification,
                transactionActive = TransactionSynchronizationManager.isActualTransactionActive()
            )
            throwOnSend?.let { throw it }

            return PushSendResult(
                sent = tokens.isNotEmpty(),
                providerMessageIds = tokens.map { "fake:${it.token}" }
            )
        }

        fun reset() {
            attempts.clear()
            throwOnSend = null
        }
    }
}
