package com.reals.backend.integration.service

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.MatchmakingQueueEntry
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.QueueStatus
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SecondChatResolutionRequest
import com.reals.backend.domain.SecondChatResolutionRequestStatus
import com.reals.backend.domain.SecondChatResolutionRequestType
import com.reals.backend.domain.UserBlock
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.domain.UserHomeStatus
import com.reals.backend.domain.UserReliabilityDimension
import com.reals.backend.domain.UserReliabilityEvent
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.domain.VisualReviewAffinityIndicator
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.localdev.LocalDevPairHistoryResetService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime
import java.util.UUID

class LocalDevPairHistoryResetServiceIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var pairHistoryResetService: LocalDevPairHistoryResetService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `reset removes only A-B pair history and preserves protected records`() {
        val affected = createActiveSecondChat()
        val firstChat = chatRepository.findByMatchIdAndChatType(affected.matchId, ChatType.FIRST_CHAT)
            ?: error("First chat not found")
        val unrelatedUserId = createActiveProfile(
            email = "pair-reset-c-${UUID.randomUUID()}@example.com",
            displayName = "Pair Reset C",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        val unrelatedMatch = matchService.createMatch(affected.userAId, unrelatedUserId)
        val unrelatedChat = chatService.startFirstChat(unrelatedMatch.id)
        matchmakingQueueRepository.saveAndFlush(
            MatchmakingQueueEntry(
                userId = affected.userAId,
                status = QueueStatus.WAITING,
                latitude = BUENOS_AIRES_LATITUDE,
                longitude = BUENOS_AIRES_LONGITUDE,
                accuracyMeters = 50
            )
        )
        matchmakingQueueRepository.saveAndFlush(
            MatchmakingQueueEntry(
                userId = affected.userBId,
                status = QueueStatus.WAITING,
                latitude = BUENOS_AIRES_LATITUDE,
                longitude = BUENOS_AIRES_LONGITUDE,
                accuracyMeters = 50
            )
        )

        val secondChatMessage = chatMessageRepository.saveAndFlush(
            ChatMessage(
                chatSessionId = affected.secondChatId,
                senderId = affected.userAId,
                content = "second chat message"
            )
        )
        val promptSnapshot = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(firstChat.id).first()
        val promptReplyMessage = chatMessageRepository.saveAndFlush(
            ChatMessage(
                chatSessionId = firstChat.id,
                senderId = affected.userAId,
                content = "reply to prompt snapshot",
                replyToPromptSnapshotId = promptSnapshot.id
            )
        )
        chatMessageRepository.saveAndFlush(
            ChatMessage(
                chatSessionId = unrelatedChat.id,
                senderId = affected.userAId,
                content = "unrelated message"
            )
        )
        assertNotNull(firstChatGuidanceRepository.findByChatId(firstChat.id))
        assertTrue(countRows("conversation_prompt_snapshots") > 0)
        visualReviewAffinityIndicatorRepository.saveAndFlush(
            VisualReviewAffinityIndicator(
                matchId = affected.matchId,
                ordinal = 1,
                categoryId = "music",
                categoryTitle = "Music"
            )
        )
        secondChatResolutionRequestRepository.saveAndFlush(
            SecondChatResolutionRequest(
                connectionId = affected.connectionId,
                chatId = affected.secondChatId,
                referenceMessageId = secondChatMessage.id,
                requesterUserId = affected.userAId,
                responderUserId = affected.userBId,
                type = SecondChatResolutionRequestType.MUTUAL_COMPLETION,
                status = SecondChatResolutionRequestStatus.PENDING,
                expiresAt = OffsetDateTime.now().plusMinutes(5)
            )
        )
        val safetyReport = safetyReportRepository.saveAndFlush(
            SafetyReport(
                reporterUserId = affected.userAId,
                reportedUserId = affected.userBId,
                chatId = affected.secondChatId,
                matchId = affected.matchId,
                connectionId = affected.connectionId,
                contextType = SafetyReportContextType.CHAT,
                contextId = affected.secondChatId,
                reason = SafetyReportReason.OTHER,
                details = "Preserved safety report"
            )
        )
        val userBlock = userBlockRepository.saveAndFlush(
            UserBlock(
                blockerUserId = affected.userAId,
                blockedUserId = affected.userBId,
                source = UserBlockSource.MANUAL
            )
        )
        listOf(affected.userAId, affected.userBId).forEach { userId ->
            homeStatusRepository.saveAndFlush(UserHomeStatus(userId = userId, dirty = false))
        }
        val affectedReliabilityEvent = userReliabilityEventRepository.saveAndFlush(
            reliabilityEvent(
                userId = affected.userAId,
                relatedMatchId = affected.matchId,
                eventType = UserReliabilityEventType.VISUAL_REVIEW_EXPIRED_NO_DECISION
            )
        )
        val unrelatedUserReliabilityEventOnAffectedAggregate = userReliabilityEventRepository.saveAndFlush(
            reliabilityEvent(
                userId = unrelatedUserId,
                relatedMatchId = affected.matchId,
                eventType = UserReliabilityEventType.FIRST_CHAT_EXPIRED_NO_DECISION
            )
        )
        val unrelatedReliabilityEvent = userReliabilityEventRepository.saveAndFlush(
            reliabilityEvent(
                userId = affected.userAId,
                relatedMatchId = unrelatedMatch.id,
                eventType = UserReliabilityEventType.FIRST_CHAT_EXPIRED_NO_DECISION
            )
        )
        pushNotificationDeliveryRepository.saveAndFlush(
            PushNotificationDelivery(
                userId = affected.userAId,
                notificationType = PushNotificationType.SECOND_CHAT_STARTED,
                aggregateId = affected.secondChatId,
                status = PushDeliveryStatus.SENT
            )
        )
        val unrelatedUserPushOnAffectedAggregate = pushNotificationDeliveryRepository.saveAndFlush(
            PushNotificationDelivery(
                userId = unrelatedUserId,
                notificationType = PushNotificationType.SECOND_CHAT_STARTED,
                aggregateId = affected.secondChatId,
                status = PushDeliveryStatus.SENT
            )
        )
        val unrelatedPush = pushNotificationDeliveryRepository.saveAndFlush(
            PushNotificationDelivery(
                userId = affected.userAId,
                notificationType = PushNotificationType.MATCH_FOUND,
                aggregateId = unrelatedMatch.id,
                status = PushDeliveryStatus.SENT
            )
        )

        val result = pairHistoryResetService.resetPairHistory(affected.userAId, affected.userBId)

        assertEquals(1, result.matchesDeleted)
        assertEquals(1, result.connectionsDeleted)
        assertEquals(2, result.chatsDeleted)
        assertEquals(1, result.reliabilityEventsDeleted)
        assertTrue(userRepository.existsById(affected.userAId))
        assertTrue(userRepository.existsById(affected.userBId))
        assertNotNull(matchmakingQueueRepository.findByUserId(affected.userAId))
        assertNotNull(matchmakingQueueRepository.findByUserId(affected.userBId))
        assertTrue(userBlockRepository.existsById(userBlock.id))
        assertTrue(safetyReportRepository.existsById(safetyReport.id))
        assertNull(
            jdbcTemplate.queryForObject(
                "SELECT chat_id FROM safety_reports WHERE id = ?",
                UUID::class.java,
                safetyReport.id
            )
        )
        assertFalse(userReliabilityEventRepository.existsById(affectedReliabilityEvent.id))
        assertTrue(userReliabilityEventRepository.existsById(unrelatedUserReliabilityEventOnAffectedAggregate.id))
        assertTrue(userReliabilityEventRepository.existsById(unrelatedReliabilityEvent.id))
        assertFalse(
            lockRepository.existsByUserIdAndEngagementIdAndEngagementType(
                affected.userAId,
                affected.matchId,
                EngagementType.MATCH
            )
        )
        assertFalse(
            lockRepository.existsByUserIdAndEngagementIdAndEngagementType(
                affected.userBId,
                affected.connectionId,
                EngagementType.CONNECTION
            )
        )
        assertTrue(
            lockRepository.existsByUserIdAndEngagementIdAndEngagementType(
                affected.userAId,
                unrelatedMatch.id,
                EngagementType.MATCH
            )
        )
        assertHomeStatus(affected.userAId, expectedDirty = true, expectedVersion = 1)
        assertHomeStatus(affected.userBId, expectedDirty = true, expectedVersion = 1)
        assertTrue(matchRepository.existsById(unrelatedMatch.id))
        assertTrue(chatRepository.existsById(unrelatedChat.id))
        assertTrue(pushNotificationDeliveryRepository.existsById(unrelatedUserPushOnAffectedAggregate.id))
        assertTrue(pushNotificationDeliveryRepository.existsById(unrelatedPush.id))
        assertFalse(chatMessageRepository.existsById(promptReplyMessage.id))
        assertEquals(0, countRowsById("conversation_prompt_snapshots", "id", promptSnapshot.id))
        assertEquals(0, countRowsById("first_chat_guidance", "chat_id", firstChat.id))
        assertEquals(0, countRowsById("conversation_prompt_snapshots", "chat_id", firstChat.id))
        assertEquals(0, countRowsById("visual_review_affinity_indicators", "match_id", affected.matchId))
        assertEquals(0, countRowsById("second_chat_resolution_requests", "connection_id", affected.connectionId))

        val replay = pairHistoryResetService.resetPairHistory(affected.userAId, affected.userBId)
        assertEquals(0, replay.matchesDeleted)
        assertEquals(0, replay.connectionsDeleted)
        assertEquals(0, replay.chatsDeleted)
        assertTrue(matchRepository.existsById(unrelatedMatch.id))
        assertHomeStatus(affected.userAId, expectedDirty = true, expectedVersion = 2)
        assertHomeStatus(affected.userBId, expectedDirty = true, expectedVersion = 2)
    }

    @Test
    fun `reset creates dirty home rows when pair has no history and no home status`() {
        val userIdA = createActiveProfile(
            email = "pair-reset-no-home-a-${UUID.randomUUID()}@example.com",
            displayName = "No Home A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userIdB = createActiveProfile(
            email = "pair-reset-no-home-b-${UUID.randomUUID()}@example.com",
            displayName = "No Home B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        jdbcTemplate.update("DELETE FROM user_home_status WHERE user_id IN (?, ?)", userIdA, userIdB)

        val result = pairHistoryResetService.resetPairHistory(userIdA, userIdB)

        assertEquals(0, result.matchesDeleted)
        assertEquals(0, result.connectionsDeleted)
        assertEquals(0, result.chatsDeleted)
        assertHomeStatus(userIdA, expectedDirty = true, expectedVersion = 0)
        assertHomeStatus(userIdB, expectedDirty = true, expectedVersion = 0)
    }

    @Test
    fun `reset rejects identical users`() {
        val userId = createActiveProfile(
            email = "pair-reset-same-${UUID.randomUUID()}@example.com",
            displayName = "Same User",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        assertThrows(IllegalArgumentException::class.java) {
            pairHistoryResetService.resetPairHistory(userId, userId)
        }
    }

    @Test
    fun `reset rejects missing users before cleanup`() {
        val existingUserId = createActiveProfile(
            email = "pair-reset-missing-${UUID.randomUUID()}@example.com",
            displayName = "Existing User",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        assertThrows(DomainNotFoundException::class.java) {
            pairHistoryResetService.resetPairHistory(existingUserId, UUID.randomUUID())
        }
    }

    private fun reliabilityEvent(
        userId: UUID,
        relatedMatchId: UUID,
        eventType: UserReliabilityEventType
    ): UserReliabilityEvent =
        UserReliabilityEvent(
            userId = userId,
            relatedMatchId = relatedMatchId,
            eventType = eventType,
            dimension = UserReliabilityDimension.ResponsivenessScore,
            delta = -1,
            occurredAt = OffsetDateTime.now(),
            expiresAt = OffsetDateTime.now().plusDays(30)
        )

    private fun countRows(table: String): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java) ?: 0

    private fun countRowsById(
        table: String,
        column: String,
        id: UUID
    ): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table WHERE $column = ?", Int::class.java, id) ?: 0

    private fun assertHomeStatus(
        userId: UUID,
        expectedDirty: Boolean,
        expectedVersion: Long
    ) {
        val homeStatus = jdbcTemplate.queryForMap(
            "SELECT dirty, version FROM user_home_status WHERE user_id = ?",
            userId
        )
        assertEquals(expectedDirty, homeStatus["DIRTY"])
        assertEquals(expectedVersion, (homeStatus["VERSION"] as Number).toLong())
    }
}
