package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushDeviceToken
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.PushPlatform
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.PushNotification
import com.reals.backend.service.PushNotificationSender
import com.reals.backend.service.PushSendResult
import com.reals.backend.service.VisualReviewNotificationService
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.OffsetDateTime
import java.util.UUID

@Import(PushNotificationIntegrationTest.PushSenderTestConfig::class)
class PushNotificationIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var pushSender: RecordingPushNotificationSender

    @Autowired
    private lateinit var visualReviewNotificationService: VisualReviewNotificationService

    @BeforeEach
    fun resetPushSender() {
        pushSender.reset()
    }

    @Test
    fun `registering a new push token creates token row`() {
        val user = userService.createUser("push-token-${UUID.randomUUID()}@example.com")

        pushDeviceTokenService.registerToken(
            userId = user.id,
            token = " fcm-token-new ",
            platform = PushPlatform.ANDROID
        )

        val token = pushDeviceTokenRepository.findByToken("fcm-token-new")
        assertNotNull(token)
        assertEquals(user.id, token?.userId)
        assertEquals(PushPlatform.ANDROID, token?.platform)
        assertTrue(token?.enabled == true)
    }

    @Test
    fun `registering same token updates last seen and keeps enabled`() {
        val firstUser = userService.createUser("push-token-first-${UUID.randomUUID()}@example.com")
        val secondUser = userService.createUser("push-token-second-${UUID.randomUUID()}@example.com")

        val original =
            pushDeviceTokenService.registerToken(
                userId = firstUser.id,
                token = "same-fcm-token",
                platform = PushPlatform.ANDROID
            )
        val oldLastSeenAt = OffsetDateTime.now().minusDays(1)
        original.enabled = false
        original.lastSeenAt = oldLastSeenAt
        original.updatedAt = oldLastSeenAt
        pushDeviceTokenRepository.saveAndFlush(original)

        val updated =
            pushDeviceTokenService.registerToken(
                userId = secondUser.id,
                token = " same-fcm-token ",
                platform = PushPlatform.ANDROID
            )

        assertEquals(original.id, updated.id)
        assertEquals(secondUser.id, updated.userId)
        assertTrue(updated.enabled)
        assertTrue(updated.lastSeenAt.isAfter(oldLastSeenAt))
    }

    @Test
    fun `visual review available sends notification to both users with active tokens`() {
        val setup = createMatchWithFirstChat("push-send")
        pushDeviceTokenService.registerToken(setup.userAId, "token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "token-b", PushPlatform.ANDROID)

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        assertEquals(0, pushSender.attempts.size)

        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        assertEquals(2, pushSender.attempts.size)
        assertEquals(listOf("token-a", "token-b"), pushSender.attempts.flatMap { it.tokens }.sorted())

        pushSender.attempts.forEach { attempt ->
            assertEquals("Tenés una revisión disponible", attempt.notification.title)
            assertEquals(
                "Ya podés revisar el perfil visual de una conversación reciente.",
                attempt.notification.body
            )
            assertEquals("VISUAL_REVIEW_AVAILABLE", attempt.notification.data["type"])
            assertEquals(setup.matchId.toString(), attempt.notification.data["matchId"])
        }

        val deliveries = deliveriesFor(setup.matchId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SENT })
    }

    @Test
    fun `duplicate visual review notification call does not create duplicate deliveries`() {
        val setup = createMatchWithFirstChat("push-dedup")
        pushDeviceTokenService.registerToken(setup.userAId, "dedup-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "dedup-token-b", PushPlatform.ANDROID)

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)
        assertEquals(2, deliveriesFor(setup.matchId).size)
        assertEquals(2, pushSender.attempts.size)

        visualReviewNotificationService.notifyVisualReviewAvailable(setup.matchId)

        assertEquals(2, deliveriesFor(setup.matchId).size)
        assertEquals(2, pushSender.attempts.size)
    }

    @Test
    fun `no active token creates skipped delivery and does not fail`() {
        val setup = createMatchWithFirstChat("push-skipped")

        assertDoesNotThrow {
            chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
            chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)
        }

        val deliveries = deliveriesFor(setup.matchId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN })
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `sender failure creates failed delivery and does not throw`() {
        val setup = createMatchWithFirstChat("push-failed")
        pushDeviceTokenService.registerToken(setup.userAId, "failed-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "failed-token-b", PushPlatform.ANDROID)
        pushSender.throwOnSend = RuntimeException("provider unavailable")

        assertDoesNotThrow {
            chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
            chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)
        }

        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)
        assertNotNull(visualReviewRepository.findByMatchId(setup.matchId))

        val deliveries = deliveriesFor(setup.matchId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.FAILED })
        assertTrue(deliveries.all { it.errorMessage?.contains("provider unavailable") == true })
    }

    @Test
    fun `invalid token is disabled when sender reports it`() {
        val setup = createMatchWithFirstChat("push-invalid")
        pushDeviceTokenService.registerToken(setup.userAId, "invalid-token", PushPlatform.ANDROID)
        pushSender.nextResult =
            PushSendResult(
                sent = false,
                invalidTokens = listOf("invalid-token"),
                errorMessage = "registration token is not registered"
            )

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        val token = pushDeviceTokenRepository.findByToken("invalid-token")
            ?: error("Expected token to exist")
        assertFalse(token.enabled)

        val deliveryForUserA = pushNotificationDeliveryRepository
            .findByUserIdAndNotificationTypeAndAggregateId(
                userId = setup.userAId,
                notificationType = PushNotificationType.VISUAL_REVIEW_AVAILABLE,
                aggregateId = setup.matchId
            )
            ?: error("Expected delivery for user A")
        assertEquals(PushDeliveryStatus.FAILED, deliveryForUserA.status)
    }

    private fun deliveriesFor(matchId: UUID) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.VISUAL_REVIEW_AVAILABLE,
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
            val notification: PushNotification
        )

        val attempts: MutableList<Attempt> = mutableListOf()
        var nextResult: PushSendResult? = null
        var throwOnSend: RuntimeException? = null

        override fun sendToTokens(
            tokens: List<PushDeviceToken>,
            notification: PushNotification
        ): PushSendResult {
            attempts += Attempt(
                tokens = tokens.map { it.token },
                notification = notification
            )

            throwOnSend?.let { throw it }

            return nextResult ?: PushSendResult(
                sent = tokens.isNotEmpty(),
                providerMessageIds = tokens.map { "fake:${it.token}" }
            )
        }

        fun reset() {
            attempts.clear()
            nextResult = null
            throwOnSend = null
        }
    }
}
