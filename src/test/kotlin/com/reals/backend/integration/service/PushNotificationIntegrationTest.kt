package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Gender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.PushPlatform
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.scheduler.SecondChatReminderNotificationJob
import com.reals.backend.scheduler.SecondChatStartNotificationJob
import com.reals.backend.scheduler.VisualReviewReminderNotificationJob
import com.reals.backend.scheduler.SchedulingActivationJob
import com.reals.backend.service.MatchFoundEvent
import com.reals.backend.service.SchedulingConfirmedEvent
import com.reals.backend.service.SchedulingProposalsReceivedEvent
import com.reals.backend.service.notification.MatchFoundNotificationService
import com.reals.backend.service.notification.SchedulingAvailableNotificationService
import com.reals.backend.service.notification.SchedulingConfirmedNotificationService
import com.reals.backend.service.notification.SchedulingProposalsReceivedNotificationService
import com.reals.backend.service.notification.SecondChatReminderNotificationService
import com.reals.backend.service.notification.SecondChatStartNotificationService
import com.reals.backend.service.notification.VisualReviewReminderNotificationService
import com.reals.backend.service.notification.schedulingAvailableAggregateId
import com.reals.backend.service.notification.schedulingProposalsReceivedAggregateId
import com.reals.backend.service.notification.secondChatReminderAggregateId
import com.reals.backend.service.notification.secondChatNotificationTag
import com.reals.backend.service.notification.secondChatStartedAggregateId
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationAndroidPriority
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
import org.springframework.data.domain.PageRequest
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
    private lateinit var matchFoundNotificationService: MatchFoundNotificationService

    @Autowired
    private lateinit var schedulingAvailableNotificationService: SchedulingAvailableNotificationService

    @Autowired
    private lateinit var schedulingProposalsReceivedNotificationService: SchedulingProposalsReceivedNotificationService

    @Autowired
    private lateinit var schedulingConfirmedNotificationService: SchedulingConfirmedNotificationService

    @Autowired
    private lateinit var secondChatReminderNotificationService: SecondChatReminderNotificationService

    @Autowired
    private lateinit var secondChatReminderNotificationJob: SecondChatReminderNotificationJob

    @Autowired
    private lateinit var secondChatStartNotificationService: SecondChatStartNotificationService

    @Autowired
    private lateinit var secondChatStartNotificationJob: SecondChatStartNotificationJob

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
    fun `match found notification sends data only expiry contract to both participants`() {
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
        val timeoutAt = now.plusMinutes(5)
        val setup = createMatchWithFirstChat("match-found-contract")
        updateFirstChatTimeoutAt(setup.firstChatId, timeoutAt)
        pushDeviceTokenService.registerToken(setup.userAId, "match-token-a-1", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userAId, "match-token-a-2", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "match-token-b-1", PushPlatform.ANDROID)

        matchFoundNotificationService.notifyMatchFound(
            event = MatchFoundEvent(
                matchId = setup.matchId,
                chatId = setup.firstChatId
            ),
            now = now
        )

        assertEquals(2, pushSender.attempts.size)
        assertEquals(
            listOf("match-token-a-1", "match-token-a-2", "match-token-b-1"),
            pushSender.attempts.flatMap { it.tokens }.sorted()
        )
        assertProviderCallsOutsideTransactions()
        pushSender.attempts.forEach { attempt ->
            assertEquals("Encontramos un chat", attempt.notification.title)
            assertEquals("Tu nuevo chat ya está disponible.", attempt.notification.body)
            assertEquals(PushNotificationType.MATCH_FOUND, PushNotificationType.valueOf(attempt.notification.data.getValue("type")))
            assertEquals(setOf("type", "matchId", "expiresAt"), attempt.notification.data.keys)
            assertEquals(setup.matchId.toString(), attempt.notification.data["matchId"])
            assertEquals(timeoutAt.toString(), attempt.notification.data["expiresAt"])
            assertEquals(Duration.between(now, timeoutAt).toMillis(), attempt.notification.androidTtlMillis)
            assertFalse(attempt.notification.includeNotificationPayload)
            assertEquals(PushNotificationAndroidPriority.HIGH, attempt.notification.androidPriority)
            assertEquals(null, attempt.notification.androidNotificationTag)
        }
        val deliveries = matchFoundDeliveriesFor(setup.matchId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SENT })

        pushSender.reset()

        matchFoundNotificationService.notifyMatchFound(
            event = MatchFoundEvent(
                matchId = setup.matchId,
                chatId = setup.firstChatId
            ),
            now = now
        )

        assertEquals(0, pushSender.attempts.size)
        assertEquals(2, matchFoundDeliveriesFor(setup.matchId).size)
    }

    @Test
    fun `match found notification skips ineligible match and chat states`() {
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")

        listOf(
            "non chat active match" to {
                val setup = eligibleMatchFoundSetup("match-found-non-chat-active", now)
                val match = matchRepository.findById(setup.matchId).orElseThrow()
                match.state = MatchState.VISUAL_PHASE
                matchRepository.saveAndFlush(match)
                setup to MatchFoundEvent(matchId = setup.matchId, chatId = setup.firstChatId)
            },
            "chat from different match" to {
                val setup = eligibleMatchFoundSetup("match-found-wrong-chat-match", now)
                val other = createMatchWithFirstChat("match-found-other-chat")
                setup to MatchFoundEvent(matchId = setup.matchId, chatId = other.firstChatId)
            },
            "non first chat" to {
                val setup = eligibleMatchFoundSetup("match-found-second-chat", now)
                val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
                chat.chatType = ChatType.SECOND_CHAT
                chatRepository.saveAndFlush(chat)
                setup to MatchFoundEvent(matchId = setup.matchId, chatId = setup.firstChatId)
            },
            "non active chat" to {
                val setup = eligibleMatchFoundSetup("match-found-non-active-chat", now)
                val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
                chat.status = ChatStatus.CANCELLED
                chatRepository.saveAndFlush(chat)
                setup to MatchFoundEvent(matchId = setup.matchId, chatId = setup.firstChatId)
            },
            "timeout exactly now" to {
                val setup = eligibleMatchFoundSetup("match-found-timeout-equal", now)
                updateFirstChatTimeoutAt(setup.firstChatId, now)
                setup to MatchFoundEvent(matchId = setup.matchId, chatId = setup.firstChatId)
            },
            "timeout before now" to {
                val setup = eligibleMatchFoundSetup("match-found-timeout-before", now)
                updateFirstChatTimeoutAt(setup.firstChatId, now.minusNanos(1))
                setup to MatchFoundEvent(matchId = setup.matchId, chatId = setup.firstChatId)
            }
        ).forEach { (scenario, prepare) ->
            pushSender.reset()
            val (setup, event) = prepare()

            matchFoundNotificationService.notifyMatchFound(event = event, now = now)

            assertEquals(0, pushSender.attempts.size, scenario)
            assertEquals(0, matchFoundDeliveriesFor(setup.matchId).size, scenario)
        }
    }

    @Test
    fun `match found notification records no token skip and skips existing delivery`() {
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
        val setup = createMatchWithFirstChat("match-found-skips")
        updateFirstChatTimeoutAt(setup.firstChatId, now.plusMinutes(5))
        pushNotificationDeliveryRepository.saveAndFlush(
            PushNotificationDelivery(
                userId = setup.userAId,
                notificationType = PushNotificationType.MATCH_FOUND,
                aggregateId = setup.matchId,
                sentAt = now.minusSeconds(1),
                status = PushDeliveryStatus.SENT,
                providerMessageId = "existing-provider-id",
                createdAt = now.minusSeconds(1),
                updatedAt = now.minusSeconds(1)
            )
        )

        matchFoundNotificationService.notifyMatchFound(
            event = MatchFoundEvent(
                matchId = setup.matchId,
                chatId = setup.firstChatId
            ),
            now = now
        )

        val deliveries = matchFoundDeliveriesFor(setup.matchId)
        assertEquals(0, pushSender.attempts.size)
        assertEquals(2, deliveries.size)
        assertEquals(PushDeliveryStatus.SENT, deliveries.first { it.userId == setup.userAId }.status)
        assertEquals(PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN, deliveries.first { it.userId == setup.userBId }.status)
    }

    @Test
    fun `match found notification processes duplicate participant id once`() {
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
        val setup = eligibleMatchFoundSetup("match-found-duplicate-participant", now)
        val match = matchRepository.findById(setup.matchId).orElseThrow()
        match.userBId = setup.userAId
        matchRepository.saveAndFlush(match)
        pushDeviceTokenService.registerToken(setup.userAId, "match-duplicate-token", PushPlatform.ANDROID)

        matchFoundNotificationService.notifyMatchFound(
            event = MatchFoundEvent(
                matchId = setup.matchId,
                chatId = setup.firstChatId
            ),
            now = now
        )

        assertEquals(1, pushSender.attempts.size)
        assertEquals(
            listOf("match-duplicate-token", "match-found-duplicate-participant-token-a"),
            pushSender.attempts.single().tokens.sorted()
        )
        assertEquals(1, matchFoundDeliveriesFor(setup.matchId).size)
    }

    @Test
    fun `matchmaking commits match and chat when match found push fails`() {
        val userA = createActiveProfile(
            email = "match-found-failure-a-${UUID.randomUUID()}@example.com",
            displayName = "Match Push Failure A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "match-found-failure-b-${UUID.randomUUID()}@example.com",
            displayName = "Match Push Failure B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        pushDeviceTokenService.registerToken(userA, "match-failure-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(userB, "match-failure-token-b", PushPlatform.ANDROID)
        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)
        pushSender.throwOnSend = RuntimeException("provider unavailable")

        val result = matchmakingProcessorService.process(maxPairsPerRun = 1)

        assertEquals(1, result.matchesCreated)
        val matchId = result.matches.single().id
        val chat = chatRepository.findByMatchIdAndChatType(matchId, ChatType.FIRST_CHAT)
            ?: error("Expected first chat")
        assertEquals(MatchState.CHAT_ACTIVE, matchRepository.findById(matchId).orElseThrow().state)
        assertEquals(ChatStatus.ACTIVE, chat.status)
        assertEquals(2, pushSender.attempts.size)
        val deliveries = matchFoundDeliveriesFor(matchId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.FAILED })
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
        assertEquals(2, pushSender.attempts.size)
        assertTrue(
            pushSender.attempts.all {
                PushNotificationType.valueOf(it.notification.data.getValue("type")) ==
                    PushNotificationType.MATCH_FOUND_INVALIDATED
            }
        )
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
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
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
    fun `scheduling available sends generic grouped notification to both users and deduplicates`() {
        val setup = createConnectionInSchedulingPhase()
        pushDeviceTokenService.registerToken(setup.userAId, "scheduling-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "scheduling-token-b", PushPlatform.ANDROID)

        schedulingAvailableNotificationService.notifySchedulingAvailable(listOf(setup.connectionId))

        assertEquals(2, pushSender.attempts.size)
        assertEquals(
            listOf("scheduling-token-a", "scheduling-token-b"),
            pushSender.attempts.flatMap { it.tokens }.sorted()
        )
        assertProviderCallsOutsideTransactions()

        pushSender.attempts.forEach { attempt ->
            assertEquals("Ya podés coordinar", attempt.notification.title)
            assertEquals(
                "Tenés coordinaciones disponibles para la segunda charla.",
                attempt.notification.body
            )
            assertEquals(
                setOf("type"),
                attempt.notification.data.keys
            )
            assertEquals(PushNotificationType.SCHEDULING_AVAILABLE.name, attempt.notification.data["type"])
        }

        val deliveries =
            listOf(setup.userAId, setup.userBId).flatMap { userId ->
                schedulingAvailableDeliveriesFor(
                    userId = userId,
                    connectionIds = listOf(setup.connectionId)
                )
            }
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SENT })

        schedulingAvailableNotificationService.notifySchedulingAvailable(listOf(setup.connectionId))

        val deliveriesAfterRetry =
            listOf(setup.userAId, setup.userBId).flatMap { userId ->
                schedulingAvailableDeliveriesFor(
                    userId = userId,
                    connectionIds = listOf(setup.connectionId)
                )
            }
        assertEquals(2, deliveriesAfterRetry.size)
        assertEquals(2, pushSender.attempts.size)
    }

    @Test
    fun `scheduling available creates skipped deliveries when users have no active tokens`() {
        val setup = createConnectionInSchedulingPhase()

        assertDoesNotThrow {
            schedulingAvailableNotificationService.notifySchedulingAvailable(listOf(setup.connectionId))
        }

        val deliveries =
            listOf(setup.userAId, setup.userBId).flatMap { userId ->
                schedulingAvailableDeliveriesFor(
                    userId = userId,
                    connectionIds = listOf(setup.connectionId)
                )
            }
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
            schedulingAvailableNotificationService.notifySchedulingAvailable(listOf(setup.connectionId))
        }

        val deliveries =
            listOf(setup.userAId, setup.userBId).flatMap { userId ->
                schedulingAvailableDeliveriesFor(
                    userId = userId,
                    connectionIds = listOf(setup.connectionId)
                )
            }
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

        schedulingAvailableNotificationService.notifySchedulingAvailable(listOf(setup.connectionId))

        val token = pushDeviceTokenRepository.findByToken("scheduling-invalid-token")
            ?: error("Expected token to exist")
        assertFalse(token.enabled)

        val deliveryForUserA = pushNotificationDeliveryRepository
            .findByUserIdAndNotificationTypeAndAggregateId(
                userId = setup.userAId,
                notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                aggregateId = schedulingAvailableAggregateId(
                    userId = setup.userAId,
                    connectionIds = listOf(setup.connectionId)
                )
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

        schedulingAvailableNotificationService.notifySchedulingAvailable(listOf(connection.id))

        val deliveries =
            listOf(setup.userAId, setup.userBId).flatMap { userId ->
                schedulingAvailableDeliveriesFor(
                    userId = userId,
                    connectionIds = listOf(connection.id)
                )
            }
        assertEquals(0, deliveries.size)
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
        val deliveries =
            listOf(setup.userAId, setup.userBId).flatMap { userId ->
                schedulingAvailableDeliveriesFor(
                    userId = userId,
                    connectionIds = listOf(connection.id)
                )
            }
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.FAILED })
        assertEquals(2, pushSender.attempts.size)
    }

    @Test
    fun `proposal list submission notifies only partner with privacy safe once per round payload`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        pushDeviceTokenService.registerToken(setup.userAId, "proposal-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "proposal-token-b", PushPlatform.ANDROID)

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot)
        )

        assertEquals(1, pushSender.attempts.size)
        val attempt = pushSender.attempts.single()
        assertEquals(listOf("proposal-token-b"), attempt.tokens)
        assertProviderCallsOutsideTransactions()
        assertEquals("Recibiste nuevas opciones", attempt.notification.title)
        assertEquals("Hay nuevas propuestas para coordinar la segunda charla.", attempt.notification.body)
        assertFalse(attempt.notification.title.contains("Match A"))
        assertFalse(attempt.notification.body.contains(slot.toString()))
        assertEquals(
            mapOf(
                "type" to PushNotificationType.SCHEDULING_PROPOSALS_RECEIVED.name,
                "connectionId" to setup.connectionId.toString(),
                "matchId" to setup.matchId.toString(),
                "roundNumber" to "1"
            ),
            attempt.notification.data
        )

        schedulingProposalsReceivedNotificationService.notifyProposalsReceived(
            SchedulingProposalsReceivedEvent(
                connectionId = setup.connectionId,
                triggeringUserId = setup.userAId,
                recipientUserId = setup.userBId,
                roundNumber = 1
            )
        )

        assertEquals(1, pushSender.attempts.size)
        assertEquals(
            1,
            schedulingProposalsReceivedDeliveriesFor(
                connectionId = setup.connectionId,
                roundNumber = 1
            ).size
        )
    }

    @Test
    fun `later proposal round can send another proposals received notification`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        pushDeviceTokenService.registerToken(setup.userAId, "proposal-round-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "proposal-round-token-b", PushPlatform.ANDROID)

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot)
        )
        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot.plusHours(1))
        )
        schedulingService.rejectPartnerProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            expectedRoundNumber = 1
        )
        schedulingService.rejectPartnerProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1
        )

        assertEquals(2, schedulingService.findNegotiationOrThrow(setup.connectionId).roundNumber)
        pushSender.reset()

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 2,
            proposedDateTimes = listOf(slot.plusHours(2))
        )

        assertEquals(1, pushSender.attempts.size)
        assertEquals(listOf("proposal-round-token-b"), pushSender.attempts.single().tokens)
        assertEquals("2", pushSender.attempts.single().notification.data["roundNumber"])
        assertEquals(
            1,
            schedulingProposalsReceivedDeliveriesFor(
                connectionId = setup.connectionId,
                roundNumber = 2
            ).size
        )
    }

    @Test
    fun `auto confirmation sends only confirmed notification to non triggering participant`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        pushDeviceTokenService.registerToken(setup.userAId, "auto-confirm-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "auto-confirm-token-b", PushPlatform.ANDROID)

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot)
        )
        pushSender.reset()

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot)
        )

        assertEquals(NegotiationStatus.CONFIRMED, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertEquals(ConnectionState.SECOND_CHAT_SCHEDULED, connectionRepository.findById(setup.connectionId).orElseThrow().state)
        assertEquals(1, pushSender.attempts.size)
        val attempt = pushSender.attempts.single()
        assertEquals(listOf("auto-confirm-token-a"), attempt.tokens)
        assertEquals(PushNotificationType.SCHEDULING_CONFIRMED.name, attempt.notification.data["type"])
        assertEquals(setup.connectionId.toString(), attempt.notification.data["connectionId"])
        assertEquals(setup.matchId.toString(), attempt.notification.data["matchId"])
        assertEquals(slot.toString(), attempt.notification.data["availableAt"])
        assertEquals("La segunda charla quedó coordinada", attempt.notification.title)
        assertEquals("El horario ya está confirmado. Revisalo en la app.", attempt.notification.body)
        assertFalse(attempt.notification.body.contains(slot.toString()))
    }

    @Test
    fun `explicit acceptance sends confirmed notification to proposal owner and deduplicates`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot(),
            expectedRoundNumber = 1
        )
        pushDeviceTokenService.registerToken(setup.userAId, "accept-confirm-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "accept-confirm-token-b", PushPlatform.ANDROID)
        pushSender.reset()

        schedulingService.acceptProposal(
            connectionId = setup.connectionId,
            proposalId = proposal.id,
            acceptorUserId = setup.userBId
        )

        assertEquals(1, pushSender.attempts.size)
        assertEquals(listOf("accept-confirm-token-a"), pushSender.attempts.single().tokens)
        assertEquals(PushNotificationType.SCHEDULING_CONFIRMED.name, pushSender.attempts.single().notification.data["type"])

        schedulingConfirmedNotificationService.notifySchedulingConfirmed(
            SchedulingConfirmedEvent(
                connectionId = setup.connectionId,
                triggeringUserId = setup.userBId
            )
        )

        assertEquals(1, pushSender.attempts.size)
        assertEquals(1, schedulingConfirmedDeliveriesFor(setup.connectionId).size)
    }

    @Test
    fun `confirmation notification failure does not roll back confirmed scheduling`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot(),
            expectedRoundNumber = 1
        )
        pushDeviceTokenService.registerToken(setup.userAId, "confirm-failure-token-a", PushPlatform.ANDROID)
        pushSender.throwOnSend = RuntimeException("provider unavailable")

        assertDoesNotThrow {
            schedulingService.acceptProposal(
                connectionId = setup.connectionId,
                proposalId = proposal.id,
                acceptorUserId = setup.userBId
            )
        }

        assertEquals(NegotiationStatus.CONFIRMED, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertEquals(ConnectionState.SECOND_CHAT_SCHEDULED, connectionRepository.findById(setup.connectionId).orElseThrow().state)
        assertEquals(1, pushSender.attempts.size)
        assertEquals(PushDeliveryStatus.FAILED, schedulingConfirmedDeliveriesFor(setup.connectionId).single().status)
    }

    @Test
    fun `scheduling activation job groups available pushes per user and preserves per user failure isolation`() {
        val sharedUserId = createActiveProfile(
            email = "group-shared-${UUID.randomUUID()}@example.com",
            displayName = "Shared User",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val first = createPendingConnectionForUsers(sharedUserId, createGroupedPartner("group-one"))
        val second = createPendingConnectionForUsers(sharedUserId, createGroupedPartner("group-two"))
        listOf(first.connectionId, second.connectionId).forEach { connectionId ->
            connectionRepository.updateSchedulingAvailableAt(
                connectionId = connectionId,
                availableAt = OffsetDateTime.now().minusSeconds(1)
            )
        }
        pushDeviceTokenService.registerToken(sharedUserId, "group-token-shared", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(first.userBId, "group-token-one", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(second.userBId, "group-token-two", PushPlatform.ANDROID)
        pushSender.failingTokens = setOf("group-token-shared")

        SchedulingActivationJob(
            connectionRepository = connectionRepository,
            schedulingService = schedulingService,
            schedulingAvailableNotificationService = schedulingAvailableNotificationService
        ).run()

        assertEquals(3, pushSender.attempts.size)
        assertEquals(
            listOf("group-token-one", "group-token-shared", "group-token-two"),
            pushSender.attempts.flatMap { it.tokens }.sorted()
        )
        pushSender.attempts.forEach { attempt ->
            assertEquals(setOf("type"), attempt.notification.data.keys)
            assertEquals(PushNotificationType.SCHEDULING_AVAILABLE.name, attempt.notification.data["type"])
        }
        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionRepository.findById(first.connectionId).orElseThrow().state)
        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionRepository.findById(second.connectionId).orElseThrow().state)
        assertNotNull(schedulingService.findNegotiationOrNull(first.connectionId))
        assertNotNull(schedulingService.findNegotiationOrNull(second.connectionId))

        val sharedDeliveries =
            schedulingAvailableDeliveriesFor(
                userId = sharedUserId,
                connectionIds = listOf(first.connectionId, second.connectionId)
            )
        assertEquals(PushDeliveryStatus.FAILED, sharedDeliveries.single().status)
        assertEquals(
            PushDeliveryStatus.SENT,
            schedulingAvailableDeliveriesFor(
                userId = first.userBId,
                connectionIds = listOf(first.connectionId)
            ).single().status
        )
        assertEquals(
            PushDeliveryStatus.SENT,
            schedulingAvailableDeliveriesFor(
                userId = second.userBId,
                connectionIds = listOf(second.connectionId)
            ).single().status
        )

        pushSender.throwOnSend = null
        pushSender.failingTokens = emptySet()
        schedulingAvailableNotificationService.notifySchedulingAvailable(
            listOf(first.connectionId, second.connectionId)
        )
        assertEquals(3, pushSender.attempts.size)

        schedulingAvailableNotificationService.notifySchedulingAvailable(listOf(first.connectionId))
        assertEquals(4, pushSender.attempts.size)
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
    fun `second chat reminder recoverable query uses exclusive lower and inclusive upper boundaries`() {
        val now = OffsetDateTime.parse("2036-07-17T12:00:00Z")
        val windowStartExclusive = now.plusMinutes(10)
        val windowEndInclusive = now.plusMinutes(20)
        val atLowerBoundary = confirmSecondChat(confirmedDateTime = windowStartExclusive)
        val firstDue = confirmSecondChat(confirmedDateTime = windowStartExclusive.plusSeconds(1))
        val tieA = confirmSecondChat(confirmedDateTime = windowStartExclusive.plusMinutes(2))
        val tieB = confirmSecondChat(confirmedDateTime = windowStartExclusive.plusMinutes(2))
        replaceNegotiationId(
            connectionId = tieA.connectionId,
            newId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        )
        replaceNegotiationId(
            connectionId = tieB.connectionId,
            newId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        )
        val atUpperBoundary = confirmSecondChat(confirmedDateTime = windowEndInclusive)
        val afterUpperBoundary = confirmSecondChat(confirmedDateTime = windowEndInclusive.plusSeconds(1))
        val pending = confirmSecondChat(
            confirmedDateTime = windowStartExclusive.plusMinutes(1),
            status = NegotiationStatus.PENDING
        )
        val closed = confirmSecondChat(
            confirmedDateTime = windowStartExclusive.plusMinutes(1),
            state = ConnectionState.CLOSED
        )

        val dueNegotiations =
            negotiationRepository.findConfirmedSecondChatReminderRecoverableForWindow(
                windowStartExclusive = windowStartExclusive,
                windowEndInclusive = windowEndInclusive,
                pageable = PageRequest.of(0, 10)
            )
        val dueConnectionIds = dueNegotiations.map { it.connectionId }

        assertFalse(dueConnectionIds.contains(atLowerBoundary.connectionId))
        assertTrue(dueConnectionIds.contains(firstDue.connectionId))
        assertTrue(dueConnectionIds.contains(tieA.connectionId))
        assertTrue(dueConnectionIds.contains(tieB.connectionId))
        assertTrue(dueConnectionIds.contains(atUpperBoundary.connectionId))
        assertFalse(dueConnectionIds.contains(afterUpperBoundary.connectionId))
        assertFalse(dueConnectionIds.contains(pending.connectionId))
        assertFalse(dueConnectionIds.contains(closed.connectionId))
        assertEquals(
            dueNegotiations.sortedWith(
                compareBy(
                    { it.confirmedDateTime },
                    { it.id }
                )
            ).map { it.id },
            dueNegotiations.map { it.id }
        )
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
            assertEquals("second-chat-${setup.connectionId}", attempt.notification.androidNotificationTag)
            assertTrue(attempt.notification.androidTtlMillis in 1..Duration.ofMinutes(5).toMillis())
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
        val now = OffsetDateTime.parse("2040-07-17T12:00:00Z")
        val due = confirmSecondChat(confirmedDateTime = now.plusMinutes(10).plusSeconds(30))
        val notDue = confirmSecondChat(confirmedDateTime = now.plusMinutes(20))
        pushDeviceTokenService.registerToken(due.userAId, "job-due-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(due.userBId, "job-due-token-b", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(notDue.userAId, "job-not-due-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(notDue.userBId, "job-not-due-token-b", PushPlatform.ANDROID)

        secondChatReminderNotificationJob.processSecondChatReminders(now)

        assertEquals(2, secondChatReminderDeliveriesFor(due.connectionId, minutesBefore = 10).size)
        assertEquals(0, secondChatReminderDeliveriesFor(notDue.connectionId, minutesBefore = 10).size)
        assertEquals(listOf("job-due-token-a", "job-due-token-b"), pushSender.attempts.flatMap { it.tokens }.sorted())

        secondChatReminderNotificationJob.processSecondChatReminders(now.plusSeconds(30))

        assertEquals(2, secondChatReminderDeliveriesFor(due.connectionId, minutesBefore = 10).size)
        assertEquals(2, pushSender.attempts.size)
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `second chat reminder job recovers omitted backlog on later scheduler run`() {
        val now = OffsetDateTime.parse("2037-07-17T12:00:00Z")
        val first = confirmSecondChat(confirmedDateTime = now.plusMinutes(10).plusSeconds(10))
        val second = confirmSecondChat(confirmedDateTime = now.plusMinutes(10).plusSeconds(20))
        val third = confirmSecondChat(confirmedDateTime = now.plusMinutes(10).plusSeconds(30))
        registerSecondChatReminderTokens(first, "backlog-first")
        registerSecondChatReminderTokens(second, "backlog-second")
        registerSecondChatReminderTokens(third, "backlog-third")

        val firstRunSummary = secondChatReminderNotificationJob.processSecondChatReminders(now)

        assertEquals(2, firstRunSummary.processed)
        assertEquals(2, firstRunSummary.succeeded)
        assertEquals(2, secondChatReminderDeliveriesFor(first.connectionId, minutesBefore = 10).size)
        assertEquals(2, secondChatReminderDeliveriesFor(second.connectionId, minutesBefore = 10).size)
        assertEquals(0, secondChatReminderDeliveriesFor(third.connectionId, minutesBefore = 10).size)

        val secondRunSummary = secondChatReminderNotificationJob.processSecondChatReminders(now.plusMinutes(1))

        assertEquals(1, secondRunSummary.processed)
        assertEquals(1, secondRunSummary.succeeded)
        assertEquals(2, secondChatReminderDeliveriesFor(third.connectionId, minutesBefore = 10).size)
        assertEquals(6, pushSender.attempts.size)
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `second chat reminder job prefers closer reminder range and does not send stale older lead`() {
        val now = OffsetDateTime.parse("2038-07-17T12:00:00Z")
        val dueForTenMinuteReminder =
            confirmSecondChat(confirmedDateTime = now.plusMinutes(10).plusSeconds(30))
        registerSecondChatReminderTokens(dueForTenMinuteReminder, "closer")
        val job =
            SecondChatReminderNotificationJob(
                negotiationRepository = negotiationRepository,
                deliveryRepository = pushNotificationDeliveryRepository,
                reminderNotificationService = secondChatReminderNotificationService,
                fixedDelayMs = 60_000,
                reminderLeadMinutes = listOf("120", "10"),
                batchSize = 2
            )

        val summary = job.processSecondChatReminders(now)

        assertEquals(1, summary.processed)
        assertEquals(1, summary.succeeded)
        assertEquals(0, secondChatReminderDeliveriesFor(dueForTenMinuteReminder.connectionId, minutesBefore = 120).size)
        assertEquals(2, secondChatReminderDeliveriesFor(dueForTenMinuteReminder.connectionId, minutesBefore = 10).size)
        assertTrue(pushSender.attempts.all { it.notification.body == "Tenes una segunda charla programada en 10 minutos." })
    }

    @Test
    fun `second chat reminder job does not send after confirmed start`() {
        val now = OffsetDateTime.parse("2039-07-17T12:00:00Z")
        val alreadyStarted = confirmSecondChat(confirmedDateTime = now.minusSeconds(1))
        registerSecondChatReminderTokens(alreadyStarted, "started")

        val summary = secondChatReminderNotificationJob.processSecondChatReminders(now)

        assertEquals(0, summary.processed)
        assertEquals(0, secondChatReminderDeliveriesFor(alreadyStarted.connectionId, minutesBefore = 10).size)
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `second chat reminder does not prepare stale push when ttl is non positive`() {
        val now = OffsetDateTime.now()
        val alreadyStarted = confirmSecondChat(confirmedDateTime = now.minusSeconds(1))
        registerSecondChatReminderTokens(alreadyStarted, "stale-reminder")

        val eligible =
            secondChatReminderNotificationService.notifySecondChatReminder(
                connectionId = alreadyStarted.connectionId,
                confirmedDateTime = now.minusSeconds(1),
                minutesBefore = 10
            )

        assertFalse(eligible)
        assertEquals(0, secondChatReminderDeliveriesFor(alreadyStarted.connectionId, minutesBefore = 10).size)
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `second chat start candidate query uses inclusive start and latest send boundaries`() {
        val now = OffsetDateTime.parse("2041-07-17T12:00:00Z")
        val beforeStart = confirmSecondChat(confirmedDateTime = now.plusSeconds(1))
        val atStart = confirmSecondChat(confirmedDateTime = now)
        val insideWindow = confirmSecondChat(confirmedDateTime = now.minusMinutes(3))
        val atLatestBoundary = confirmSecondChat(confirmedDateTime = now.minusMinutes(5))
        val stale = confirmSecondChat(confirmedDateTime = now.minusMinutes(5).minusSeconds(1))
        val available = confirmSecondChat(
            confirmedDateTime = now.minusMinutes(1),
            state = ConnectionState.SECOND_CHAT_AVAILABLE
        )
        val secondChat = confirmSecondChat(
            confirmedDateTime = now.minusMinutes(1),
            state = ConnectionState.SECOND_CHAT
        )
        val closed = confirmSecondChat(
            confirmedDateTime = now.minusMinutes(1),
            state = ConnectionState.CLOSED
        )
        val pending = confirmSecondChat(
            confirmedDateTime = now.minusMinutes(1),
            status = NegotiationStatus.PENDING
        )

        val dueConnectionIds =
            negotiationRepository.findConfirmedSecondChatStartNotificationDueConnectionIds(
                windowStartInclusive = now.minusMinutes(5),
                now = now,
                pageable = PageRequest.of(0, 10)
            )

        assertFalse(dueConnectionIds.contains(beforeStart.connectionId))
        assertTrue(dueConnectionIds.contains(atStart.connectionId))
        assertTrue(dueConnectionIds.contains(insideWindow.connectionId))
        assertTrue(dueConnectionIds.contains(atLatestBoundary.connectionId))
        assertFalse(dueConnectionIds.contains(stale.connectionId))
        assertTrue(dueConnectionIds.contains(available.connectionId))
        assertTrue(dueConnectionIds.contains(secondChat.connectionId))
        assertFalse(dueConnectionIds.contains(closed.connectionId))
        assertFalse(dueConnectionIds.contains(pending.connectionId))
        assertEquals(5, dueConnectionIds.size)
    }

    @Test
    fun `second chat start notification sends privacy safe payload to both unjoined users and deduplicates`() {
        val now = OffsetDateTime.parse("2042-07-17T12:00:00Z")
        val setup = confirmSecondChat(confirmedDateTime = now.minusMinutes(1))
        registerSecondChatStartTokens(setup, "start-both")

        val firstResult =
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = setup.connectionId,
                now = now,
                latestSendAfterStartMinutes = 5
            )

        assertEquals(2, firstResult.succeeded)
        assertEquals(0, firstResult.skipped)
        assertEquals(listOf("start-both-token-a", "start-both-token-b"), pushSender.attempts.flatMap { it.tokens }.sorted())
        assertProviderCallsOutsideTransactions()
        pushSender.attempts.forEach { attempt ->
            assertEquals("Tu segunda charla ya empezó", attempt.notification.title)
            assertEquals("Entrá ahora a Reals para sumarte.", attempt.notification.body)
            assertEquals("second-chat-${setup.connectionId}", attempt.notification.androidNotificationTag)
            assertEquals(Duration.ofMinutes(9).toMillis(), attempt.notification.androidTtlMillis)
            assertEquals(setOf("type", "connectionId", "matchId", "availableAt"), attempt.notification.data.keys)
            assertEquals(PushNotificationType.SECOND_CHAT_STARTED.name, attempt.notification.data["type"])
            assertEquals(setup.connectionId.toString(), attempt.notification.data["connectionId"])
            assertEquals(setup.matchId.toString(), attempt.notification.data["matchId"])
            assertEquals(now.minusMinutes(1).toString(), attempt.notification.data["availableAt"])
        }

        val deliveries = secondChatStartedDeliveriesFor(setup.connectionId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SENT })

        val secondResult =
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = setup.connectionId,
                now = now.plusMinutes(1),
                latestSendAfterStartMinutes = 5
            )

        assertEquals(0, secondResult.succeeded)
        assertEquals(2, secondResult.skipped)
        assertEquals(2, secondChatStartedDeliveriesFor(setup.connectionId).size)
        assertEquals(2, pushSender.attempts.size)
        assertEquals(secondChatStartedAggregateId(setup.connectionId), secondChatStartedAggregateId(setup.connectionId))
        assertTrue(secondChatStartedAggregateId(setup.connectionId) != secondChatStartedAggregateId(UUID.randomUUID()))
    }

    @Test
    fun `second chat reminder and start notification share Android replacement tag`() {
        val reminderScheduledAt = OffsetDateTime.now().plusMinutes(3)
        val reminderSetup = confirmSecondChat(confirmedDateTime = reminderScheduledAt)
        registerSecondChatReminderTokens(reminderSetup, "shared-reminder-tag")

        secondChatReminderNotificationService.notifySecondChatReminder(
            connectionId = reminderSetup.connectionId,
            confirmedDateTime = reminderScheduledAt,
            minutesBefore = 10
        )
        val reminderTags = pushSender.attempts.map { it.notification.androidNotificationTag }.toSet()
        pushSender.reset()

        val startScheduledAt = OffsetDateTime.parse("2042-08-17T12:00:00Z")
        val startSetup = confirmSecondChat(confirmedDateTime = startScheduledAt)
        registerSecondChatStartTokens(startSetup, "shared-start-tag")

        secondChatStartNotificationService.processSecondChatStart(
            connectionId = startSetup.connectionId,
            now = startScheduledAt,
            latestSendAfterStartMinutes = 5
        )

        assertEquals(setOf(secondChatNotificationTag(reminderSetup.connectionId)), reminderTags)
        assertEquals(
            setOf(secondChatNotificationTag(startSetup.connectionId)),
            pushSender.attempts.map { it.notification.androidNotificationTag }.toSet()
        )
    }

    @Test
    fun `second chat start notification does not prepare stale push when ttl is non positive`() {
        val scheduledAt = OffsetDateTime.parse("2042-08-17T12:00:00Z")
        val setup = confirmSecondChat(confirmedDateTime = scheduledAt)
        registerSecondChatStartTokens(setup, "stale-start-ttl")

        val result =
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = setup.connectionId,
                now = scheduledAt.plusMinutes(10),
                latestSendAfterStartMinutes = 15
            )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(0, secondChatStartedDeliveriesFor(setup.connectionId).size)
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `second chat start notification skips only already joined participant`() {
        val scheduledAt = OffsetDateTime.parse("2043-07-17T12:00:00Z")
        val setup = confirmSecondChat(confirmedDateTime = scheduledAt)
        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        pushDeviceTokenService.registerToken(setup.userAId, "joined-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "unjoined-token-b", PushPlatform.ANDROID)

        val result =
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = setup.connectionId,
                now = scheduledAt.plusMinutes(2),
                latestSendAfterStartMinutes = 5
            )

        assertEquals(1, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(listOf("unjoined-token-b"), pushSender.attempts.flatMap { it.tokens })
        val deliveries = secondChatStartedDeliveriesFor(setup.connectionId)
        assertEquals(PushDeliveryStatus.SKIPPED_ALREADY_JOINED, deliveries.first { it.userId == setup.userAId }.status)
        assertEquals(PushDeliveryStatus.SENT, deliveries.first { it.userId == setup.userBId }.status)
    }

    @Test
    fun `second chat start notification skips symmetric joined participant`() {
        val scheduledAt = OffsetDateTime.parse("2044-07-17T12:00:00Z")
        val setup = confirmSecondChat(confirmedDateTime = scheduledAt)
        joinSecondChatOrThrow(setup.connectionId, setup.userBId, scheduledAt.plusMinutes(1))
        pushDeviceTokenService.registerToken(setup.userAId, "unjoined-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "joined-token-b", PushPlatform.ANDROID)

        val result =
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = setup.connectionId,
                now = scheduledAt.plusMinutes(2),
                latestSendAfterStartMinutes = 5
            )

        assertEquals(1, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(listOf("unjoined-token-a"), pushSender.attempts.flatMap { it.tokens })
        val deliveries = secondChatStartedDeliveriesFor(setup.connectionId)
        assertEquals(PushDeliveryStatus.SENT, deliveries.first { it.userId == setup.userAId }.status)
        assertEquals(PushDeliveryStatus.SKIPPED_ALREADY_JOINED, deliveries.first { it.userId == setup.userBId }.status)
    }

    @Test
    fun `second chat start notification records both joined participants without FCM dispatch`() {
        val scheduledAt = OffsetDateTime.parse("2045-07-17T12:00:00Z")
        val setup = confirmSecondChat(confirmedDateTime = scheduledAt)
        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        joinSecondChatOrThrow(setup.connectionId, setup.userBId, scheduledAt.plusMinutes(2))
        registerSecondChatStartTokens(setup, "joined-both")

        val result =
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = setup.connectionId,
                now = scheduledAt.plusMinutes(3),
                latestSendAfterStartMinutes = 5
            )

        assertEquals(0, result.succeeded)
        assertEquals(2, result.skipped)
        assertEquals(0, pushSender.attempts.size)
        assertTrue(secondChatStartedDeliveriesFor(setup.connectionId).all {
            it.status == PushDeliveryStatus.SKIPPED_ALREADY_JOINED
        })
    }

    @Test
    fun `second chat start notification does not send before start or after delivery window`() {
        val scheduledAt = OffsetDateTime.parse("2046-07-17T12:00:00Z")
        val before = confirmSecondChat(confirmedDateTime = scheduledAt)
        val stale = confirmSecondChat(confirmedDateTime = scheduledAt)
        registerSecondChatStartTokens(before, "before-start")
        registerSecondChatStartTokens(stale, "stale-start")

        val beforeResult =
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = before.connectionId,
                now = scheduledAt.minusNanos(1),
                latestSendAfterStartMinutes = 5
            )
        val staleResult =
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = stale.connectionId,
                now = scheduledAt.plusMinutes(5).plusNanos(1),
                latestSendAfterStartMinutes = 5
            )

        assertEquals(1, beforeResult.skipped)
        assertEquals(1, staleResult.skipped)
        assertEquals(0, secondChatStartedDeliveriesFor(before.connectionId).size)
        assertEquals(0, secondChatStartedDeliveriesFor(stale.connectionId).size)
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `second chat start notification records missing token skips`() {
        val scheduledAt = OffsetDateTime.parse("2047-07-17T12:00:00Z")
        val setup = confirmSecondChat(confirmedDateTime = scheduledAt)

        val result =
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = setup.connectionId,
                now = scheduledAt,
                latestSendAfterStartMinutes = 5
            )

        assertEquals(0, result.succeeded)
        assertEquals(2, result.skipped)
        assertEquals(0, pushSender.attempts.size)
        assertTrue(secondChatStartedDeliveriesFor(setup.connectionId).all {
            it.status == PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN
        })
    }

    @Test
    fun `second chat start job is bounded and fully handled candidates do not occupy next run`() {
        val now = OffsetDateTime.parse("2048-07-17T12:00:00Z")
        val first = confirmSecondChat(confirmedDateTime = now.minusMinutes(1))
        val second = confirmSecondChat(confirmedDateTime = now.minusMinutes(1).plusSeconds(1))
        val third = confirmSecondChat(confirmedDateTime = now.minusMinutes(1).plusSeconds(2))
        registerSecondChatStartTokens(first, "start-first")
        registerSecondChatStartTokens(second, "start-second")
        registerSecondChatStartTokens(third, "start-third")

        val firstRun = secondChatStartNotificationJob.processSecondChatStartNotifications(now)

        assertEquals(2, firstRun.processed)
        assertEquals(2, firstRun.succeeded)
        assertEquals(2, secondChatStartedDeliveriesFor(first.connectionId).size)
        assertEquals(2, secondChatStartedDeliveriesFor(second.connectionId).size)
        assertEquals(0, secondChatStartedDeliveriesFor(third.connectionId).size)

        val secondRun = secondChatStartNotificationJob.processSecondChatStartNotifications(now.plusMinutes(1))

        assertEquals(1, secondRun.processed)
        assertEquals(1, secondRun.succeeded)
        assertEquals(2, secondChatStartedDeliveriesFor(third.connectionId).size)
        assertEquals(6, pushSender.attempts.size)
    }

    @Test
    fun `second chat start job scans past handled candidates without exceeding batch size`() {
        val now = OffsetDateTime.parse("2048-08-17T12:00:00Z")
        val handled = (0 until 5).map { index ->
            confirmSecondChat(confirmedDateTime = now.minusMinutes(4).plusSeconds(index.toLong()))
        }
        val unhandled = (5 until 8).map { index ->
            confirmSecondChat(confirmedDateTime = now.minusMinutes(4).plusSeconds(index.toLong()))
        }
        (handled + unhandled).forEachIndexed { index, setup ->
            registerSecondChatStartTokens(setup, "scan-$index")
        }

        handled.forEach { setup ->
            secondChatStartNotificationService.processSecondChatStart(
                connectionId = setup.connectionId,
                now = now,
                latestSendAfterStartMinutes = 5
            )
        }
        pushSender.reset()

        val summary = secondChatStartNotificationJob.processSecondChatStartNotifications(now)

        assertEquals(2, summary.processed)
        assertEquals(2, summary.succeeded)
        assertEquals(0, summary.skipped)
        assertEquals(0, summary.failed)
        assertEquals(4, pushSender.attempts.size)
        assertEquals(2, secondChatStartedDeliveriesFor(unhandled[0].connectionId).size)
        assertEquals(2, secondChatStartedDeliveriesFor(unhandled[1].connectionId).size)
        assertEquals(0, secondChatStartedDeliveriesFor(unhandled[2].connectionId).size)
    }

    @Test
    fun `second chat start job observes participant join between executions`() {
        val scheduledAt = OffsetDateTime.parse("2049-07-17T12:00:00Z")
        val setup = confirmSecondChat(confirmedDateTime = scheduledAt)
        registerSecondChatStartTokens(setup, "between-runs")

        secondChatStartNotificationJob.processSecondChatStartNotifications(scheduledAt.minusSeconds(1))
        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        secondChatStartNotificationJob.processSecondChatStartNotifications(scheduledAt.plusMinutes(2))

        assertEquals(listOf("between-runs-token-b"), pushSender.attempts.flatMap { it.tokens })
        val deliveries = secondChatStartedDeliveriesFor(setup.connectionId)
        assertEquals(PushDeliveryStatus.SKIPPED_ALREADY_JOINED, deliveries.first { it.userId == setup.userAId }.status)
        assertEquals(PushDeliveryStatus.SENT, deliveries.first { it.userId == setup.userBId }.status)
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

    private fun matchFoundDeliveriesFor(matchId: UUID) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.MATCH_FOUND,
            aggregateId = matchId
        )

    private fun visualReviewReminderDeliveriesFor(matchId: UUID) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
            aggregateId = matchId
        )

    private fun schedulingAvailableDeliveriesFor(
        userId: UUID,
        connectionIds: Collection<UUID>
    ) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
            aggregateId = schedulingAvailableAggregateId(
                userId = userId,
                connectionIds = connectionIds
            )
        )

    private fun schedulingProposalsReceivedDeliveriesFor(
        connectionId: UUID,
        roundNumber: Int
    ) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.SCHEDULING_PROPOSALS_RECEIVED,
            aggregateId = schedulingProposalsReceivedAggregateId(
                connectionId = connectionId,
                roundNumber = roundNumber
            )
        )

    private fun schedulingConfirmedDeliveriesFor(connectionId: UUID) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.SCHEDULING_CONFIRMED,
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

    private fun secondChatStartedDeliveriesFor(connectionId: UUID) =
        pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.SECOND_CHAT_STARTED,
            aggregateId = secondChatStartedAggregateId(connectionId)
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

    private fun replaceNegotiationId(
        connectionId: UUID,
        newId: UUID
    ) {
        val existing = negotiationRepository.findByConnectionId(connectionId)
            ?: error("Expected scheduling negotiation")
        negotiationRepository.delete(existing)
        negotiationRepository.flush()
        negotiationRepository.saveAndFlush(
            existing.copy(
                id = newId,
                version = 0
            )
        )
    }

    private fun registerSecondChatReminderTokens(
        setup: ConnectionFixture,
        tokenPrefix: String
    ) {
        pushDeviceTokenService.registerToken(setup.userAId, "$tokenPrefix-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "$tokenPrefix-token-b", PushPlatform.ANDROID)
    }

    private fun registerSecondChatStartTokens(
        setup: ConnectionFixture,
        tokenPrefix: String
    ) {
        pushDeviceTokenService.registerToken(setup.userAId, "$tokenPrefix-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "$tokenPrefix-token-b", PushPlatform.ANDROID)
    }

    private fun createGroupedPartner(prefix: String): UUID =
        createActiveProfile(
            email = "$prefix-${UUID.randomUUID()}@example.com",
            displayName = "Grouped Partner",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

    private fun createPendingConnectionForUsers(
        userAId: UUID,
        userBId: UUID
    ): ConnectionFixture {
        val match = matchService.createMatch(userAId, userBId)
        chatService.startFirstChat(match.id)
        chatService.recordChatDecision(match.id, userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(match.id, userBId, ChatContinueDecision.APPROVED)
        visualReviewService.makeAvailableNowForTest(match.id)
        visualReviewService.recordDecision(match.id, userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(match.id, userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(match.id)
            ?: error("Connection was not created")

        return ConnectionFixture(
            userAId = userAId,
            userBId = userBId,
            matchId = match.id,
            connectionId = connection.id
        )
    }

    private fun createDueVisualReview(emailPrefix: String): MatchFixture =
        createVisualReviewWithWindow(
            emailPrefix = emailPrefix,
            reminderEligibleAt = OffsetDateTime.now().minusSeconds(1),
            expiresAt = OffsetDateTime.now().plusHours(1)
        )

    private fun eligibleMatchFoundSetup(
        emailPrefix: String,
        now: OffsetDateTime
    ): MatchFixture {
        val setup = createMatchWithFirstChat(emailPrefix)
        updateFirstChatTimeoutAt(setup.firstChatId, now.plusMinutes(5))
        pushDeviceTokenService.registerToken(setup.userAId, "$emailPrefix-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "$emailPrefix-token-b", PushPlatform.ANDROID)
        return setup
    }

    private fun updateFirstChatTimeoutAt(
        chatId: UUID,
        timeoutAt: OffsetDateTime
    ) {
        val chat = chatRepository.findById(chatId).orElseThrow()
        chat.timeoutAt = timeoutAt
        chatRepository.saveAndFlush(chat)
    }

    private fun createVisualReviewWithWindow(
        emailPrefix: String,
        reminderEligibleAt: OffsetDateTime?,
        expiresAt: OffsetDateTime
    ): MatchFixture {
        val setup = createMatchWithFirstChat(emailPrefix)
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)
        visualReviewService.makeAvailableNowForTest(setup.matchId)
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

