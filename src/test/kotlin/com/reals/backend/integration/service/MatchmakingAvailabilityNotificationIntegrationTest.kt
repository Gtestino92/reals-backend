package com.reals.backend.integration.service

import com.reals.backend.domain.ActiveEngagementLock
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.MatchmakingAvailabilityNotificationEpisode
import com.reals.backend.domain.MatchmakingAvailabilityNotificationEpisodeStatus
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.PushPlatform
import com.reals.backend.domain.VisualReview
import com.reals.backend.integration.BaseIT
import com.reals.backend.repository.MatchmakingAvailabilityNotificationEpisodeRepository
import com.reals.backend.scheduler.MatchmakingAvailabilityNotificationJob
import com.reals.backend.service.NotificationPreferenceService
import com.reals.backend.service.NotificationPreferenceSettings
import com.reals.backend.service.matching.VisualAdvancementCapService
import com.reals.backend.service.notification.MatchmakingAvailabilityNotificationService
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationSender
import com.reals.backend.service.notification.sender.PushNotificationToken
import com.reals.backend.service.notification.sender.PushSendResult
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
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Import(MatchmakingAvailabilityNotificationIntegrationTest.PushSenderTestConfig::class)
@TestPropertySource(
    properties = [
        "matchmaking.visual-advancement.max-per-window=2",
        "matchmaking.visual-advancement.window-hours=24",
        "scheduler.matchmaking-availability-notification-job.batch-size=10",
        "scheduler.matchmaking-availability-notification-job.discovery-batch-size=10"
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatchmakingAvailabilityNotificationIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var episodeRepository: MatchmakingAvailabilityNotificationEpisodeRepository

    @Autowired
    private lateinit var visualAdvancementCapService: VisualAdvancementCapService

    @Autowired
    private lateinit var availabilityNotificationService: MatchmakingAvailabilityNotificationService

    @Autowired
    private lateinit var availabilityNotificationJob: MatchmakingAvailabilityNotificationJob

    @Autowired
    private lateinit var notificationPreferenceService: NotificationPreferenceService

    @Autowired
    private lateinit var pushSender: RecordingPushNotificationSender

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    fun resetPushSender() {
        episodeRepository.deleteAll()
        pushNotificationDeliveryRepository.deleteAll()
        pushSender.reset()
    }

    @Test
    fun `reaching visual cap creates one pending episode and replay does not duplicate it`() {
        val userId = activeFemale("availability-create")
        val partnerOne = activeMale("availability-create-one")
        val partnerTwo = activeMale("availability-create-two")

        createVisualReviewThroughFirstChat(userId, partnerOne)
        assertEquals(0, pendingEpisodes(userId).size)

        val secondReview = createVisualReviewThroughFirstChat(userId, partnerTwo)
        val episode = pendingEpisode(userId)
        val capStatus = visualAdvancementCapService.statusFor(userId, secondReview.createdAt)

        assertNotNull(episode)
        assertEquals(capStatus.nextAvailableAt?.toInstant(), episode?.nextCheckAt?.toInstant())

        visualReviewService.initializeForMatch(secondReview.matchId)

        assertEquals(1, pendingEpisodes(userId).size)
    }

    @Test
    fun `new visual review is included immediately and both participants reconcile independently`() {
        val userA = activeFemale("availability-both-a")
        val userB = activeMale("availability-both-b")
        createVisualReviewThroughFirstChat(userA, activeMale("availability-both-a-prior"))
        createVisualReviewThroughFirstChat(activeFemale("availability-both-b-prior"), userB)

        val sharedReview = createVisualReviewThroughFirstChat(userA, userB)

        assertEquals(2L, visualReviewRepository.countAdvancementsForUserCreatedAfter(userA, sharedReview.createdAt.minusHours(24)))
        assertEquals(2L, visualReviewRepository.countAdvancementsForUserCreatedAfter(userB, sharedReview.createdAt.minusHours(24)))
        assertEquals(1, pendingEpisodes(userA).size)
        assertEquals(1, pendingEpisodes(userB).size)
    }

    @Test
    fun `valid overshoot still creates visual review and moves pending threshold later`() {
        val userId = activeFemale("availability-overshoot")
        val partnerA = activeMale("availability-overshoot-a")
        val partnerB = activeMale("availability-overshoot-b")
        val partnerC = activeMale("availability-overshoot-c")
        val base = OffsetDateTime.parse("2040-08-21T10:00:00Z")
        val alreadyAdmittedMatch = createFirstChat(userId, partnerC)

        val reviewA = saveVisualAdvancement(userId, partnerA, base)
        saveVisualAdvancement(userId, partnerB, base.plusHours(1))
        availabilityNotificationService.discoverMissingOrStaleEpisodes(
            now = base.plusHours(2),
            maxUsers = 10
        )
        val initialEpisode = pendingEpisode(userId) ?: error("Expected pending episode")

        assertEquals(base.plusHours(24).toInstant(), initialEpisode.nextCheckAt.toInstant())

        chatService.recordChatDecision(alreadyAdmittedMatch.id, userId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(alreadyAdmittedMatch.id, partnerC, ChatContinueDecision.APPROVED)
        moveReviewCreatedAt(alreadyAdmittedMatch.id, base.plusHours(4))
        transactionTemplate.executeWithoutResult {
            availabilityNotificationService.reconcileAfterVisualAdvancementCreated(
                userIds = listOf(userId),
                now = base.plusHours(4)
            )
        }

        assertNotNull(visualReviewRepository.findByMatchId(reviewA.matchId))
        assertEquals(3L, visualReviewRepository.countAdvancementsForUserCreatedAfter(userId, base.minusSeconds(1)))
        val movedEpisode = pendingEpisode(userId) ?: error("Expected pending episode")
        assertEquals(initialEpisode.id, movedEpisode.id)
        assertEquals(base.plusHours(25).toInstant(), movedEpisode.nextCheckAt.toInstant())
    }

    @Test
    fun `cutoff equality ages out and discovery is idempotent`() {
        val userId = activeFemale("availability-discovery")
        val partnerA = activeMale("availability-discovery-a")
        val partnerB = activeMale("availability-discovery-b")
        val now = OffsetDateTime.parse("2040-08-22T10:00:00Z")
        saveVisualAdvancement(userId, partnerA, now.minusHours(24))
        saveVisualAdvancement(userId, partnerB, now.minusHours(23))

        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now)

        assertEquals(0, pendingEpisodes(userId).size)

        val partnerC = activeMale("availability-discovery-c")
        saveVisualAdvancement(userId, partnerC, now.minusHours(22))

        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now)
        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now)

        val episode = pendingEpisode(userId) ?: error("Expected pending episode")
        assertEquals(1, pendingEpisodes(userId).size)
        assertEquals(
            visualAdvancementCapService.statusFor(userId, now).nextAvailableAt?.toInstant(),
            episode.nextCheckAt.toInstant()
        )
    }

    @Test
    fun `due job updates threshold when cap remains blocked and sends when searchable`() {
        val userId = activeFemale("availability-due")
        val now = OffsetDateTime.parse("2040-08-22T10:00:00Z")
        saveVisualAdvancement(userId, activeMale("availability-due-a"), now.minusHours(24))
        saveVisualAdvancement(userId, activeMale("availability-due-b"), now.minusHours(23))
        saveVisualAdvancement(userId, activeMale("availability-due-c"), now.minusHours(20))
        val episode = savePendingEpisode(userId, now)

        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now)

        assertEquals(0, pushSender.attempts.size)
        assertEquals(MatchmakingAvailabilityNotificationEpisodeStatus.PENDING, episodeRepository.findById(episode.id).orElseThrow().status)
        assertEquals(now.plusHours(1).toInstant(), episodeRepository.findById(episode.id).orElseThrow().nextCheckAt.toInstant())

        pushDeviceTokenService.registerToken(userId, "availability-due-token", PushPlatform.ANDROID)
        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now.plusHours(1))

        assertAvailabilityPushSent(episode.id, "availability-due-token")
        val resolved = episodeRepository.findById(episode.id).orElseThrow()
        assertEquals(MatchmakingAvailabilityNotificationEpisodeStatus.HANDLED, resolved.status)

        pushSender.reset()
        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now.plusHours(1))
        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `other blocker or queue cancels cleared visual cap episode without sending`() {
        val activeMatchUser = activeFemale("availability-match-blocker")
        val queueUser = activeFemale("availability-queue-blocker")
        val now = OffsetDateTime.parse("2040-08-22T10:00:00Z")
        val activeMatchEpisode = savePendingEpisode(activeMatchUser, now)
        val queueEpisode = savePendingEpisode(queueUser, now)
        lockRepository.saveAndFlush(
            ActiveEngagementLock(
                userId = activeMatchUser,
                engagementId = UUID.randomUUID(),
                engagementType = EngagementType.MATCH
            )
        )
        repeat(4) {
            lockRepository.saveAndFlush(
                ActiveEngagementLock(
                    userId = activeMatchUser,
                    engagementId = UUID.randomUUID(),
                    engagementType = EngagementType.MATCH
                )
            )
        }
        enqueueForMatchmaking(queueUser)
        pushDeviceTokenService.registerToken(activeMatchUser, "availability-match-blocker-token", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(queueUser, "availability-queue-blocker-token", PushPlatform.ANDROID)

        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now)

        assertEquals(0, pushSender.attempts.size)
        assertEquals(MatchmakingAvailabilityNotificationEpisodeStatus.CANCELLED, episodeRepository.findById(activeMatchEpisode.id).orElseThrow().status)
        assertEquals(MatchmakingAvailabilityNotificationEpisodeStatus.CANCELLED, episodeRepository.findById(queueEpisode.id).orElseThrow().status)
    }

    @Test
    fun `availability preference off and no token both resolve without provider send`() {
        val preferenceUser = activeFemale("availability-preference-off")
        val noTokenUser = activeFemale("availability-no-token")
        val now = OffsetDateTime.parse("2040-08-22T10:00:00Z")
        val preferenceEpisode = savePendingEpisode(preferenceUser, now)
        val noTokenEpisode = savePendingEpisode(noTokenUser, now)
        pushDeviceTokenService.registerToken(preferenceUser, "availability-preference-token", PushPlatform.ANDROID)
        notificationPreferenceService.updatePreferences(
            userId = preferenceUser,
            input = NotificationPreferenceSettings(
                activityEnabled = true,
                remindersEnabled = true,
                availabilityEnabled = false
            )
        )

        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now)

        assertEquals(0, pushSender.attempts.size)
        assertDelivery(preferenceUser, preferenceEpisode.id, PushDeliveryStatus.SKIPPED_USER_PREFERENCE)
        assertDelivery(noTokenUser, noTokenEpisode.id, PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN)
        assertEquals(MatchmakingAvailabilityNotificationEpisodeStatus.HANDLED, episodeRepository.findById(preferenceEpisode.id).orElseThrow().status)
        assertEquals(MatchmakingAvailabilityNotificationEpisodeStatus.HANDLED, episodeRepository.findById(noTokenEpisode.id).orElseThrow().status)

        notificationPreferenceService.updatePreferences(
            userId = preferenceUser,
            input = NotificationPreferenceSettings(
                activityEnabled = true,
                remindersEnabled = true,
                availabilityEnabled = true
            )
        )
        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now)

        assertEquals(0, pushSender.attempts.size)
    }

    @Test
    fun `default missing availability preference allows push and soft deletion removes episode`() {
        val userId = activeFemale("availability-default-and-delete")
        val now = OffsetDateTime.parse("2040-08-22T10:00:00Z")
        val episode = savePendingEpisode(userId, now)
        pushDeviceTokenService.registerToken(userId, "availability-default-token", PushPlatform.ANDROID)

        availabilityNotificationJob.processMatchmakingAvailabilityNotifications(now)

        assertAvailabilityPushSent(episode.id, "availability-default-token")
        val futureEpisode = savePendingEpisode(userId, now.plusHours(1))
        assertTrue(futureEpisode.id != episode.id)
        assertEquals(1, pendingEpisodes(userId).size)

        val pendingDeleteUser = activeFemale("availability-delete")
        savePendingEpisode(pendingDeleteUser, now)
        userService.deleteUser(pendingDeleteUser)

        assertEquals(0, episodeRepository.countByUserIdAndStatus(pendingDeleteUser, MatchmakingAvailabilityNotificationEpisodeStatus.PENDING))
    }

    private fun createVisualReviewThroughFirstChat(
        userAId: UUID,
        userBId: UUID
    ): VisualReview {
        val match = matchService.createMatch(userAId, userBId)
        chatService.startFirstChat(match.id)
        chatService.recordChatDecision(match.id, userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(match.id, userBId, ChatContinueDecision.APPROVED)
        return visualReviewRepository.findByMatchId(match.id) ?: error("Expected visual review")
    }

    private fun createFirstChat(
        userAId: UUID,
        userBId: UUID
    ): Match {
        val match = matchService.createMatch(userAId, userBId)
        chatService.startFirstChat(match.id)
        return match
    }

    private fun saveVisualAdvancement(
        userId: UUID,
        partnerId: UUID,
        createdAt: OffsetDateTime
    ): VisualReview {
        val match = matchRepository.saveAndFlush(
            Match(
                userAId = userId,
                userBId = partnerId,
                state = MatchState.VISUAL_PHASE,
                createdAt = createdAt.minusMinutes(1),
                updatedAt = createdAt
            )
        )
        return visualReviewRepository.saveAndFlush(
            VisualReview(
                matchId = match.id,
                expiresAt = createdAt.plusHours(24),
                availableAt = createdAt,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        )
    }

    private fun moveReviewCreatedAt(
        matchId: UUID,
        createdAt: OffsetDateTime
    ) {
        val review = visualReviewRepository.findByMatchId(matchId) ?: error("Expected visual review")
        review.createdAt = createdAt
        review.updatedAt = createdAt
        visualReviewRepository.saveAndFlush(review)
    }

    private fun savePendingEpisode(
        userId: UUID,
        nextCheckAt: OffsetDateTime
    ): MatchmakingAvailabilityNotificationEpisode =
        episodeRepository.saveAndFlush(
            MatchmakingAvailabilityNotificationEpisode(
                userId = userId,
                nextCheckAt = nextCheckAt,
                createdAt = nextCheckAt.minusHours(1),
                updatedAt = nextCheckAt.minusHours(1)
            )
        )

    private fun pendingEpisode(userId: UUID): MatchmakingAvailabilityNotificationEpisode? =
        episodeRepository.findByUserIdAndStatus(
            userId = userId,
            status = MatchmakingAvailabilityNotificationEpisodeStatus.PENDING
        )

    private fun pendingEpisodes(userId: UUID): List<MatchmakingAvailabilityNotificationEpisode> =
        episodeRepository.findAll().filter {
            it.userId == userId &&
                it.status == MatchmakingAvailabilityNotificationEpisodeStatus.PENDING
        }

    private fun assertAvailabilityPushSent(
        episodeId: UUID,
        token: String
    ) {
        assertEquals(1, pushSender.attempts.size)
        val attempt = pushSender.attempts.single()
        assertEquals(listOf(token), attempt.tokens)
        assertFalse(attempt.transactionActive)
        assertEquals("Ya podés buscar de nuevo", attempt.notification.title)
        assertEquals("Cuando quieras, podés volver a buscar a alguien nuevo.", attempt.notification.body)
        assertEquals(mapOf("type" to PushNotificationType.MATCHMAKING_AVAILABLE.name), attempt.notification.data)
        assertEquals(Duration.ofHours(1).toMillis(), attempt.notification.androidTtlMillis)
        assertEquals("matchmaking-availability", attempt.notification.androidNotificationTag)
        assertEquals(null, attempt.notification.androidPriority)
        val deliveries =
            pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
                notificationType = PushNotificationType.MATCHMAKING_AVAILABLE,
                aggregateId = episodeId
            )
        assertEquals(1, deliveries.size)
        assertEquals(PushDeliveryStatus.SENT, deliveries.single().status)
    }

    private fun assertDelivery(
        userId: UUID,
        episodeId: UUID,
        status: PushDeliveryStatus
    ) {
        val delivery =
            pushNotificationDeliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                userId = userId,
                notificationType = PushNotificationType.MATCHMAKING_AVAILABLE,
                aggregateId = episodeId
            )
        assertNotNull(delivery)
        assertEquals(status, delivery?.status)
    }

    private fun activeFemale(prefix: String): UUID =
        createActiveProfile(
            email = "$prefix-${UUID.randomUUID()}@example.com",
            displayName = prefix,
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

    private fun activeMale(prefix: String): UUID =
        createActiveProfile(
            email = "$prefix-${UUID.randomUUID()}@example.com",
            displayName = prefix,
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
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

        override fun sendToTokens(
            tokens: List<PushNotificationToken>,
            notification: PushNotification
        ): PushSendResult {
            attempts += Attempt(
                tokens = tokens.map { it.token },
                notification = notification,
                transactionActive = TransactionSynchronizationManager.isActualTransactionActive()
            )
            return PushSendResult(
                sent = tokens.isNotEmpty(),
                providerMessageIds = tokens.map { "availability:${it.token}" }
            )
        }

        fun reset() {
            attempts.clear()
        }
    }
}
