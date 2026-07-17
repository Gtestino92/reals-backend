package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.PushPlatform
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.scheduler.SecondChatReminderNotificationJob
import com.reals.backend.scheduler.VisualReviewReminderNotificationJob
import com.reals.backend.scheduler.SchedulingActivationJob
import com.reals.backend.service.notification.SchedulingAvailableNotificationService
import com.reals.backend.service.notification.SecondChatReminderNotificationService
import com.reals.backend.service.notification.VisualReviewReminderNotificationService
import com.reals.backend.service.notification.secondChatReminderAggregateId
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationSender
import com.reals.backend.service.notification.sender.PushNotificationToken
import com.reals.backend.service.notification.sender.PushSendResult
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(PushNotificationIntegrationTest.PushSenderTestConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PushNotificationIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var pushSender: RecordingPushNotificationSender

    @Autowired
    private lateinit var visualReviewReminderNotificationService: VisualReviewReminderNotificationService

    @Autowired
    private lateinit var visualReviewReminderNotificationJob: VisualReviewReminderNotificationJob

    @Autowired
    private lateinit var schedulingAvailableNotificationService: SchedulingAvailableNotificationService

    @Autowired
    private lateinit var secondChatReminderNotificationService: SecondChatReminderNotificationService

    @Autowired
    private lateinit var secondChatReminderNotificationJob: SecondChatReminderNotificationJob

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
        assertEquals(PushPlatform.ANDROID, updated.platform)
        assertTrue(updated.enabled)
    }

    @Test
    fun `mutual first chat approval creates visual review without immediate push`() {
        val setup = createMatchWithFirstChat("push-no-immediate")
        pushDeviceTokenService.registerToken(setup.userAId, "token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "token-b", PushPlatform.ANDROID)

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        assertEquals(0, pushSender.attempts.size)

        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)
        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        val expiresAt = review.expiresAt ?: error("Expected visual review expiresAt")
        val reminderEligibleAt = review.reminderEligibleAt ?: error("Expected visual review reminderEligibleAt")
        assertTrue(reminderEligibleAt.isAfter(review.createdAt))
        assertTrue(reminderEligibleAt.isBefore(expiresAt))
        assertEquals(
            Duration.between(review.createdAt, expiresAt).seconds * 60 / 100,
            Duration.between(review.createdAt, reminderEligibleAt).seconds
        )
        assertEquals(0, pushSender.attempts.size)
        assertEquals(0, visualReviewAvailableDeliveriesFor(setup.matchId).size)
        assertEquals(0, visualReviewReminderDeliveriesFor(setup.matchId).size)
    }

    @Test
    fun `visual review initialization returns existing legacy row unchanged`() {
        val userA = createActiveProfile(
            email = "legacy-visual-a-${UUID.randomUUID()}@example.com",
            displayName = "Legacy Visual A",
            gender = com.reals.backend.domain.Gender.FEMALE,
            lookingForGenders = setOf(com.reals.backend.domain.Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "legacy-visual-b-${UUID.randomUUID()}@example.com",
            displayName = "Legacy Visual B",
            gender = com.reals.backend.domain.Gender.MALE,
            lookingForGenders = setOf(com.reals.backend.domain.Gender.FEMALE)
        )
        val match = matchService.createMatch(userA, userB)
        val existing = visualReviewRepository.saveAndFlush(
            com.reals.backend.domain.VisualReview(
                matchId = match.id,
                expiresAt = OffsetDateTime.now().plusHours(1),
                reminderEligibleAt = null
            )
        )

        val returned = visualReviewService.initializeForMatch(match.id)

        assertEquals(existing.id, returned.id)
        assertEquals(null, returned.reminderEligibleAt)
    }

    @Test
    fun `visual review reminder sends to both pending users and deduplicates`() {
        val setup = createDueVisualReview("push-reminder-both")
        pushDeviceTokenService.registerToken(setup.userAId, "dedup-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "dedup-token-b", PushPlatform.ANDROID)

        visualReviewReminderNotificationJob.runNowForDev()

        assertEquals(2, visualReviewReminderDeliveriesFor(setup.matchId).size)
        assertEquals(2, pushSender.attempts.size)
        assertEquals(listOf("dedup-token-a", "dedup-token-b"), pushSender.attempts.flatMap { it.tokens }.sorted())
        assertProviderCallsOutsideTransactions()
        pushSender.attempts.forEach { attempt ->
            assertEquals("Tu revisión vence pronto", attempt.notification.title)
            assertEquals(
                "Tenés una revisión pendiente. Entrá a Reals para completarla antes de que venza.",
                attempt.notification.body
            )
            assertEquals(PushNotificationType.VISUAL_REVIEW_REMINDER.name, attempt.notification.data["type"])
            assertEquals(setup.matchId.toString(), attempt.notification.data["matchId"])
        }

        visualReviewReminderNotificationJob.runNowForDev()

        assertEquals(2, visualReviewReminderDeliveriesFor(setup.matchId).size)
        assertEquals(2, pushSender.attempts.size)
    }

    @Test
    fun `visual review reminder sends only to user whose own decision is pending`() {
        val setup = createDueVisualReview("push-reminder-one")
        pushDeviceTokenService.registerToken(setup.userAId, "pending-a-token", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "pending-b-token", PushPlatform.ANDROID)

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.REJECTED)

        visualReviewReminderNotificationJob.runNowForDev()

        assertEquals(listOf("pending-b-token"), pushSender.attempts.flatMap { it.tokens })
        val deliveries = visualReviewReminderDeliveriesFor(setup.matchId)
        assertEquals(1, deliveries.size)
        assertEquals(setup.userBId, deliveries.single().userId)
        assertEquals(PushDeliveryStatus.SENT, deliveries.single().status)
    }

    @Test
    fun `visual review reminder sends to user A when user B already decided`() {
        val setup = createDueVisualReview("push-reminder-a-pending")
        pushDeviceTokenService.registerToken(setup.userAId, "pending-a-only-token", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "decided-b-token", PushPlatform.ANDROID)

        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        visualReviewReminderNotificationJob.runNowForDev()

        assertEquals(listOf("pending-a-only-token"), pushSender.attempts.flatMap { it.tokens })
        val deliveries = visualReviewReminderDeliveriesFor(setup.matchId)
        assertEquals(1, deliveries.size)
        assertEquals(setup.userAId, deliveries.single().userId)
        assertEquals(PushDeliveryStatus.SENT, deliveries.single().status)
    }

    @Test
    fun `visual review reminder honors exact eligibility and expiration boundaries`() {
        val now = OffsetDateTime.now()
        val exactlyEligible = createVisualReviewWithWindow(
            emailPrefix = "push-exact-eligible",
            reminderEligibleAt = now,
            expiresAt = now.plusHours(1)
        )
        val exactlyExpired = createVisualReviewWithWindow(
            emailPrefix = "push-exact-expired",
            reminderEligibleAt = now.minusHours(1),
            expiresAt = now
        )

        val persistedEligibleAt = visualReviewRepository.findByMatchId(exactlyEligible.matchId)
            ?.reminderEligibleAt
            ?: error("Expected persisted reminder eligibility")
        val persistedExpiresAt = visualReviewRepository.findByMatchId(exactlyExpired.matchId)
            ?.expiresAt
            ?: error("Expected persisted expiration")

        val eligibleCandidates = visualReviewRepository.findVisualReviewReminderCandidates(persistedEligibleAt)
            .map { it.matchId }
        val expiredResult = visualReviewReminderNotificationService.processReminder(exactlyExpired.matchId, persistedExpiresAt)

        assertTrue(eligibleCandidates.contains(exactlyEligible.matchId))
        assertEquals(1, expiredResult.skipped)
        assertEquals(0, visualReviewReminderDeliveriesFor(exactlyExpired.matchId).size)
    }

    @Test
    fun `visual review reminder job ignores ineligible reviews`() {
        val beforeEligible = createVisualReviewWithWindow(
            emailPrefix = "push-before",
            reminderEligibleAt = OffsetDateTime.now().plusMinutes(5),
            expiresAt = OffsetDateTime.now().plusHours(1)
        )
        val expired = createVisualReviewWithWindow(
            emailPrefix = "push-expired",
            reminderEligibleAt = OffsetDateTime.now().minusHours(1),
            expiresAt = OffsetDateTime.now().minusSeconds(1)
        )
        val legacyNullEligible = createVisualReviewWithWindow(
            emailPrefix = "push-null",
            reminderEligibleAt = null,
            expiresAt = OffsetDateTime.now().plusHours(1)
        )
        val outsideVisualPhase = createDueVisualReview("push-outside")
        matchRepository.findById(outsideVisualPhase.matchId).orElseThrow().also { match ->
            match.state = MatchState.EXPIRED
            match.updatedAt = OffsetDateTime.now()
            matchRepository.saveAndFlush(match)
        }
        val bothDecided = createDueVisualReview("push-decided")
        visualReviewService.recordDecision(bothDecided.matchId, bothDecided.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(bothDecided.matchId, bothDecided.userBId, VisualDecision.APPROVED)

        listOf(beforeEligible, expired, legacyNullEligible, outsideVisualPhase, bothDecided)
            .forEach { setup ->
                pushDeviceTokenService.registerToken(
                    setup.userAId,
                    "ineligible-a-${setup.matchId}",
                    PushPlatform.ANDROID
                )
                pushDeviceTokenService.registerToken(
                    setup.userBId,
                    "ineligible-b-${setup.matchId}",
                    PushPlatform.ANDROID
                )
            }

        visualReviewReminderNotificationJob.runNowForDev()

        listOf(beforeEligible, expired, legacyNullEligible, outsideVisualPhase, bothDecided)
            .forEach { setup ->
                assertEquals(0, visualReviewReminderDeliveriesFor(setup.matchId).size)
            }
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `legacy visual review available delivery does not suppress reminder`() {
        val setup = createDueVisualReview("push-legacy")
        pushDeviceTokenService.registerToken(setup.userAId, "legacy-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "legacy-token-b", PushPlatform.ANDROID)
        pushNotificationDeliveryRepository.saveAndFlush(
            com.reals.backend.domain.PushNotificationDelivery(
                userId = setup.userAId,
                notificationType = PushNotificationType.VISUAL_REVIEW_AVAILABLE,
                aggregateId = setup.matchId,
                status = PushDeliveryStatus.SENT,
                sentAt = OffsetDateTime.now()
            )
        )

        visualReviewReminderNotificationJob.runNowForDev()

        assertEquals(1, visualReviewAvailableDeliveriesFor(setup.matchId).size)
        assertEquals(2, visualReviewReminderDeliveriesFor(setup.matchId).size)
        assertEquals(2, pushSender.attempts.size)
    }

    @Test
    fun `visual review reminder creates skipped delivery when user has no active token`() {
        val setup = createDueVisualReview("push-skipped")

        assertDoesNotThrow {
            visualReviewReminderNotificationJob.runNowForDev()
        }

        val deliveries = visualReviewReminderDeliveriesFor(setup.matchId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN })
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `visual review reminder sender failure creates failed delivery and does not throw`() {
        val setup = createDueVisualReview("push-failed")
        pushDeviceTokenService.registerToken(setup.userAId, "failed-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "failed-token-b", PushPlatform.ANDROID)
        pushSender.throwOnSend = RuntimeException("provider unavailable")

        assertDoesNotThrow {
            visualReviewReminderNotificationJob.runNowForDev()
        }

        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)
        assertNotNull(visualReviewRepository.findByMatchId(setup.matchId))

        val deliveries = visualReviewReminderDeliveriesFor(setup.matchId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.FAILED })
        assertTrue(deliveries.all { it.errorMessage?.contains("provider unavailable") == true })
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `visual review reminder failure for one user does not prevent processing the other`() {
        val setup = createDueVisualReview("push-one-failure")
        pushDeviceTokenService.registerToken(setup.userAId, "failure-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "success-token-b", PushPlatform.ANDROID)
        pushSender.failingTokens = setOf("failure-token-a")

        visualReviewReminderNotificationService.processReminder(setup.matchId)

        val deliveries = visualReviewReminderDeliveriesFor(setup.matchId)
        assertEquals(2, deliveries.size)
        assertEquals(PushDeliveryStatus.FAILED, deliveries.first { it.userId == setup.userAId }.status)
        assertEquals(PushDeliveryStatus.SENT, deliveries.first { it.userId == setup.userBId }.status)
        assertEquals(listOf("failure-token-a", "success-token-b"), pushSender.attempts.flatMap { it.tokens }.sorted())
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `visual review reminder invalid token is disabled when sender reports it`() {
        val setup = createDueVisualReview("push-invalid")
        pushDeviceTokenService.registerToken(setup.userAId, "invalid-token", PushPlatform.ANDROID)
        pushSender.nextResult =
            PushSendResult(
                sent = false,
                invalidTokens = listOf("invalid-token"),
                errorMessage = "registration token is not registered"
            )

        visualReviewReminderNotificationService.processReminder(setup.matchId)

        val token = pushDeviceTokenRepository.findByToken("invalid-token")
            ?: error("Expected token to exist")
        assertFalse(token.enabled)

        val deliveryForUserA = pushNotificationDeliveryRepository
            .findByUserIdAndNotificationTypeAndAggregateId(
                userId = setup.userAId,
                notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
                aggregateId = setup.matchId
            )
            ?: error("Expected delivery for user A")
        assertEquals(PushDeliveryStatus.FAILED, deliveryForUserA.status)
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `visual review decision can complete while reminder sender is blocked`() {
        val setup = createDueVisualReview("push-lock-release")
        pushDeviceTokenService.registerToken(setup.userAId, "lock-release-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "lock-release-token-b", PushPlatform.ANDROID)
        val senderStarted = CountDownLatch(1)
        val releaseSender = CountDownLatch(1)
        pushSender.senderStarted = senderStarted
        pushSender.releaseSender = releaseSender

        val executor = Executors.newSingleThreadExecutor()
        val reminderFuture =
            executor.submit {
                visualReviewReminderNotificationService.processReminder(setup.matchId)
            }

        assertTrue(senderStarted.await(5, TimeUnit.SECONDS))

        assertDoesNotThrow {
            visualReviewService.recordDecision(
                matchId = setup.matchId,
                userId = setup.userAId,
                decision = VisualDecision.REJECTED
            )
        }

        releaseSender.countDown()
        reminderFuture.get(5, TimeUnit.SECONDS)
        executor.shutdownNow()

        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        assertEquals(VisualDecision.REJECTED, review.userAVisualDecision)
        assertTrue(visualReviewReminderDeliveriesFor(setup.matchId).isNotEmpty())
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `delivery unique race after provider call does not send twice or fail job`() {
        val setup = createDueVisualReview("push-race")
        pushDeviceTokenService.registerToken(setup.userAId, "race-token-a", PushPlatform.ANDROID)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)
        pushSender.onSend = { _, notification ->
            pushNotificationDeliveryRepository.saveAndFlush(
                com.reals.backend.domain.PushNotificationDelivery(
                    userId = setup.userAId,
                    notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
                    aggregateId = UUID.fromString(notification.data.getValue("matchId")),
                    status = PushDeliveryStatus.SENT,
                    sentAt = OffsetDateTime.now()
                )
            )
        }

        val result = visualReviewReminderNotificationService.processReminder(setup.matchId)

        assertEquals(1, pushSender.attempts.size)
        assertEquals(1, result.succeeded)
        assertEquals(1, visualReviewReminderDeliveriesFor(setup.matchId).size)
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `scheduling available sends privacy safe notification to both users and deduplicates`() {
        val setup = createConnectionInSchedulingPhase()
        pushDeviceTokenService.registerToken(setup.userAId, "scheduling-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "scheduling-token-b", PushPlatform.ANDROID)

        schedulingAvailableNotificationService.notifySchedulingAvailable(setup.connectionId)

        assertEquals(2, pushSender.attempts.size)
        assertEquals(
            listOf("scheduling-token-a", "scheduling-token-b"),
            pushSender.attempts.flatMap { it.tokens }.sorted()
        )
        assertProviderCallsOutsideTransactions()

        pushSender.attempts.forEach { attempt ->
            assertEquals("Ya pueden coordinar horarios", attempt.notification.title)
            assertEquals(
                "La coordinación para la segunda charla ya está disponible.",
                attempt.notification.body
            )
            assertEquals(
                setOf("type", "connectionId", "matchId"),
                attempt.notification.data.keys
            )
            assertEquals(PushNotificationType.SCHEDULING_AVAILABLE.name, attempt.notification.data["type"])
            assertEquals(setup.connectionId.toString(), attempt.notification.data["connectionId"])
            assertEquals(setup.matchId.toString(), attempt.notification.data["matchId"])
        }

        val deliveries = schedulingAvailableDeliveriesFor(setup.connectionId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SENT })
        assertTrue(deliveries.all { it.aggregateId == setup.connectionId })

        schedulingAvailableNotificationService.notifySchedulingAvailable(setup.connectionId)

        assertEquals(2, schedulingAvailableDeliveriesFor(setup.connectionId).size)
        assertEquals(2, pushSender.attempts.size)
    }

    @Test
    fun `scheduling available creates skipped deliveries when users have no active tokens`() {
        val setup = createConnectionInSchedulingPhase()

        assertDoesNotThrow {
            schedulingAvailableNotificationService.notifySchedulingAvailable(setup.connectionId)
        }

        val deliveries = schedulingAvailableDeliveriesFor(setup.connectionId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN })
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `scheduling available records failed deliveries when sender fails`() {
        val setup = createConnectionInSchedulingPhase()
        pushDeviceTokenService.registerToken(setup.userAId, "scheduling-failed-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "scheduling-failed-token-b", PushPlatform.ANDROID)
        pushSender.throwOnSend = RuntimeException("provider unavailable")

        assertDoesNotThrow {
            schedulingAvailableNotificationService.notifySchedulingAvailable(setup.connectionId)
        }

        val deliveries = schedulingAvailableDeliveriesFor(setup.connectionId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.FAILED })
        assertTrue(deliveries.all { it.errorMessage?.contains("provider unavailable") == true })
        assertEquals(2, pushSender.attempts.size)
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `scheduling available disables invalid tokens reported by sender`() {
        val setup = createConnectionInSchedulingPhase()
        pushDeviceTokenService.registerToken(setup.userAId, "scheduling-invalid-token", PushPlatform.ANDROID)
        pushSender.nextResult =
            PushSendResult(
                sent = false,
                invalidTokens = listOf("scheduling-invalid-token"),
                errorMessage = "registration token is not registered"
            )

        schedulingAvailableNotificationService.notifySchedulingAvailable(setup.connectionId)

        val token = pushDeviceTokenRepository.findByToken("scheduling-invalid-token")
            ?: error("Expected token to exist")
        assertFalse(token.enabled)

        val deliveryForUserA = pushNotificationDeliveryRepository
            .findByUserIdAndNotificationTypeAndAggregateId(
                userId = setup.userAId,
                notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                aggregateId = setup.connectionId
            )
            ?: error("Expected delivery for user A")
        assertEquals(PushDeliveryStatus.FAILED, deliveryForUserA.status)
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `scheduling available skips when connection is not actionable`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")
        assertEquals(ConnectionState.SCHEDULING_PENDING, connection.state)

        pushDeviceTokenService.registerToken(setup.userAId, "scheduling-pending-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "scheduling-pending-token-b", PushPlatform.ANDROID)

        schedulingAvailableNotificationService.notifySchedulingAvailable(connection.id)

        assertEquals(0, schedulingAvailableDeliveriesFor(connection.id).size)
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `scheduling activation job keeps activation successful when scheduling notification fails`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")
        connectionRepository.updateSchedulingAvailableAt(
            connectionId = connection.id,
            availableAt = OffsetDateTime.now().minusSeconds(1)
        )
        pushDeviceTokenService.registerToken(setup.userAId, "scheduling-job-failed-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "scheduling-job-failed-token-b", PushPlatform.ANDROID)
        pushSender.throwOnSend = RuntimeException("provider unavailable")

        SchedulingActivationJob(
            connectionRepository = connectionRepository,
            schedulingService = schedulingService,
            schedulingAvailableNotificationService = schedulingAvailableNotificationService
        ).run()

        assertEquals(
            ConnectionState.SCHEDULING_PHASE,
            connectionRepository.findById(connection.id).orElseThrow().state
        )
        assertNotNull(schedulingService.findNegotiationOrNull(connection.id))
        val deliveries = schedulingAvailableDeliveriesFor(connection.id)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.FAILED })
        assertEquals(2, pushSender.attempts.size)
    }

    @Test
    fun `second chat reminder due query returns only confirmed actionable upcoming negotiations`() {
        val now = OffsetDateTime.now()
        val due =
            confirmSecondChat(
                confirmedDateTime = now.plusMinutes(10).plusSeconds(30)
            )
        confirmSecondChat(
            confirmedDateTime = now.plusMinutes(20)
        )
        confirmSecondChat(
            confirmedDateTime = now.minusMinutes(1)
        )
        confirmSecondChat(
            confirmedDateTime = now.plusMinutes(5),
            status = NegotiationStatus.PENDING
        )
        confirmSecondChat(
            confirmedDateTime = now.plusMinutes(5),
            state = ConnectionState.CLOSED
        )
        val alreadyAvailable =
            confirmSecondChat(
                confirmedDateTime = now.plusMinutes(10).plusSeconds(30),
                state = ConnectionState.SECOND_CHAT_AVAILABLE
            )

        val dueNegotiations =
            negotiationRepository.findConfirmedSecondChatReminderDueForWindow(
                windowStart = now.plusMinutes(10),
                windowEnd = now.plusMinutes(11)
            )

        assertEquals(listOf(due.connectionId), dueNegotiations.map { it.connectionId })
        assertFalse(dueNegotiations.map { it.connectionId }.contains(alreadyAvailable.connectionId))

        val tooLateFor120MinuteReminder =
            confirmSecondChat(
                confirmedDateTime = now.plusMinutes(60)
            )

        val dueFor120MinuteReminder =
            negotiationRepository.findConfirmedSecondChatReminderDueForWindow(
                windowStart = now.plusMinutes(120),
                windowEnd = now.plusMinutes(121)
            )

        assertFalse(dueFor120MinuteReminder.map { it.connectionId }.contains(tooLateFor120MinuteReminder.connectionId))
    }

    @Test
    fun `second chat reminder sends privacy safe notification to both users and deduplicates`() {
        val confirmedDateTime = OffsetDateTime.now().plusMinutes(5)
        val setup = confirmSecondChat(confirmedDateTime = confirmedDateTime)
        pushDeviceTokenService.registerToken(setup.userAId, "second-chat-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "second-chat-token-b", PushPlatform.ANDROID)

        assertTrue(
            secondChatReminderNotificationService.notifySecondChatReminder(
                connectionId = setup.connectionId,
                confirmedDateTime = confirmedDateTime,
                minutesBefore = 10
            )
        )

        assertEquals(2, pushSender.attempts.size)
        assertEquals(
            listOf("second-chat-token-a", "second-chat-token-b"),
            pushSender.attempts.flatMap { it.tokens }.sorted()
        )
        assertProviderCallsOutsideTransactions()

        pushSender.attempts.forEach { attempt ->
            assertEquals("Tu segunda charla empieza pronto", attempt.notification.title)
            assertEquals("Tenes una segunda charla programada en 10 minutos.", attempt.notification.body)
            assertEquals(
                setOf("type", "connectionId", "availableAt"),
                attempt.notification.data.keys
            )
            assertEquals(PushNotificationType.SECOND_CHAT_REMINDER.name, attempt.notification.data["type"])
            assertEquals(setup.connectionId.toString(), attempt.notification.data["connectionId"])
            assertEquals(confirmedDateTime.toString(), attempt.notification.data["availableAt"])
        }

        val deliveries = secondChatReminderDeliveriesFor(setup.connectionId, minutesBefore = 10)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SENT })

        secondChatReminderNotificationService.notifySecondChatReminder(
            connectionId = setup.connectionId,
            confirmedDateTime = confirmedDateTime,
            minutesBefore = 10
        )

        assertEquals(2, secondChatReminderDeliveriesFor(setup.connectionId, minutesBefore = 10).size)
        assertEquals(2, pushSender.attempts.size)

        secondChatReminderNotificationService.notifySecondChatReminder(
            connectionId = setup.connectionId,
            confirmedDateTime = confirmedDateTime,
            minutesBefore = 120
        )

        assertEquals(2, secondChatReminderDeliveriesFor(setup.connectionId, minutesBefore = 120).size)
        assertEquals(4, pushSender.attempts.size)
    }

    @Test
    fun `second chat reminder creates skipped deliveries when users have no active tokens`() {
        val setup = confirmSecondChat(confirmedDateTime = OffsetDateTime.now().plusMinutes(5))

        assertDoesNotThrow {
            secondChatReminderNotificationService.notifySecondChatReminder(
                connectionId = setup.connectionId,
                confirmedDateTime = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime
                    ?: error("Expected confirmed time"),
                minutesBefore = 10
            )
        }

        val deliveries = secondChatReminderDeliveriesFor(setup.connectionId, minutesBefore = 10)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN })
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `second chat reminder disables invalid tokens reported by sender`() {
        val confirmedDateTime = OffsetDateTime.now().plusMinutes(5)
        val setup = confirmSecondChat(confirmedDateTime = confirmedDateTime)
        pushDeviceTokenService.registerToken(setup.userAId, "second-chat-invalid-token", PushPlatform.ANDROID)
        pushSender.nextResult =
            PushSendResult(
                sent = false,
                invalidTokens = listOf("second-chat-invalid-token"),
                errorMessage = "registration token is not registered"
            )

        secondChatReminderNotificationService.notifySecondChatReminder(
            connectionId = setup.connectionId,
            confirmedDateTime = confirmedDateTime,
            minutesBefore = 10
        )

        val token = pushDeviceTokenRepository.findByToken("second-chat-invalid-token")
            ?: error("Expected token to exist")
        assertFalse(token.enabled)

        val deliveryForUserA = pushNotificationDeliveryRepository
            .findByUserIdAndNotificationTypeAndAggregateId(
                userId = setup.userAId,
                notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
                aggregateId = secondChatReminderAggregateId(
                    connectionId = setup.connectionId,
                    minutesBefore = 10
                )
            )
            ?: error("Expected delivery for user A")
        assertEquals(PushDeliveryStatus.FAILED, deliveryForUserA.status)
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `second chat reminder job sends due reminders once`() {
        val due = confirmSecondChat(confirmedDateTime = OffsetDateTime.now().plusMinutes(10).plusSeconds(30))
        val notDue = confirmSecondChat(confirmedDateTime = OffsetDateTime.now().plusMinutes(20))
        pushDeviceTokenService.registerToken(due.userAId, "job-due-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(due.userBId, "job-due-token-b", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(notDue.userAId, "job-not-due-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(notDue.userBId, "job-not-due-token-b", PushPlatform.ANDROID)

        secondChatReminderNotificationJob.runNowForDev()

        assertEquals(2, secondChatReminderDeliveriesFor(due.connectionId, minutesBefore = 10).size)
        assertEquals(0, secondChatReminderDeliveriesFor(notDue.connectionId, minutesBefore = 10).size)
        assertEquals(listOf("job-due-token-a", "job-due-token-b"), pushSender.attempts.flatMap { it.tokens }.sorted())

        secondChatReminderNotificationJob.runNowForDev()

        assertEquals(2, secondChatReminderDeliveriesFor(due.connectionId, minutesBefore = 10).size)
        assertEquals(2, pushSender.attempts.size)
        assertProviderCallsOutsideTransactions()
    }

    private fun assertProviderCallsOutsideTransactions() {
        assertTrue(pushSender.attempts.isNotEmpty())
        assertTrue(pushSender.attempts.none { it.transactionActive })
    }

    private fun visualReviewAvailableDeliveriesFor(matchId: UUID) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.VISUAL_REVIEW_AVAILABLE,
            aggregateId = matchId
        )

    private fun visualReviewReminderDeliveriesFor(matchId: UUID) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
            aggregateId = matchId
        )

    private fun schedulingAvailableDeliveriesFor(connectionId: UUID) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
            aggregateId = connectionId
        )

    private fun secondChatReminderDeliveriesFor(
        connectionId: UUID,
        minutesBefore: Long
    ) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
            aggregateId = secondChatReminderAggregateId(
                connectionId = connectionId,
                minutesBefore = minutesBefore
            )
        )

    private fun confirmSecondChat(
        confirmedDateTime: OffsetDateTime,
        state: ConnectionState = ConnectionState.SECOND_CHAT_SCHEDULED,
        status: NegotiationStatus = NegotiationStatus.CONFIRMED
    ): ConnectionFixture {
        val setup = createConnectionInSchedulingPhase()
        val connection = connectionRepository.findById(setup.connectionId).orElseThrow()
        connection.state = state
        connection.updatedAt = OffsetDateTime.now()
        connectionRepository.saveAndFlush(connection)

        val negotiation = negotiationRepository.findByConnectionId(setup.connectionId)
            ?: error("Expected scheduling negotiation")
        negotiation.status = status
        negotiation.confirmedDateTime = confirmedDateTime
        negotiation.updatedAt = OffsetDateTime.now()
        negotiationRepository.saveAndFlush(negotiation)

        return setup
    }

    private fun createDueVisualReview(emailPrefix: String): MatchFixture =
        createVisualReviewWithWindow(
            emailPrefix = emailPrefix,
            reminderEligibleAt = OffsetDateTime.now().minusSeconds(1),
            expiresAt = OffsetDateTime.now().plusHours(1)
        )

    private fun createVisualReviewWithWindow(
        emailPrefix: String,
        reminderEligibleAt: OffsetDateTime?,
        expiresAt: OffsetDateTime
    ): MatchFixture {
        val setup = createMatchWithFirstChat(emailPrefix)
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)
        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        review.reminderEligibleAt = reminderEligibleAt
        review.expiresAt = expiresAt
        review.updatedAt = OffsetDateTime.now()
        visualReviewRepository.saveAndFlush(review)

        return setup
    }

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
        var nextResult: PushSendResult? = null
        var throwOnSend: RuntimeException? = null
        var failingTokens: Set<String> = emptySet()
        var senderStarted: CountDownLatch? = null
        var releaseSender: CountDownLatch? = null
        var onSend: ((List<PushNotificationToken>, PushNotification) -> Unit)? = null

        override fun sendToTokens(
            tokens: List<PushNotificationToken>,
            notification: PushNotification
        ): PushSendResult {
            attempts += Attempt(
                tokens = tokens.map { it.token },
                notification = notification,
                transactionActive = TransactionSynchronizationManager.isActualTransactionActive()
            )
            onSend?.invoke(tokens, notification)
            senderStarted?.countDown()
            releaseSender?.await(5, TimeUnit.SECONDS)

            throwOnSend?.let { throw it }
            if (tokens.any { failingTokens.contains(it.token) }) {
                throw RuntimeException("fcm unavailable for token")
            }

            return nextResult ?: PushSendResult(
                sent = tokens.isNotEmpty(),
                providerMessageIds = tokens.map { "fake:${it.token}" }
            )
        }

        fun reset() {
            attempts.clear()
            nextResult = null
            throwOnSend = null
            failingTokens = emptySet()
            senderStarted = null
            releaseSender = null
            onSend = null
        }
    }
}
