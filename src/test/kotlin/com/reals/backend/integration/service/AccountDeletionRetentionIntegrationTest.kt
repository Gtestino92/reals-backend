package com.reals.backend.integration.service

import com.reals.backend.domain.ActiveEngagementLock
import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEvent
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ConnectionHomeDismissal
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.domain.MatchmakingQueueEntry
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyType
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.PushPlatform
import com.reals.backend.domain.QueueStatus
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportEvidenceSnapshot
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.UserBlock
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.domain.UserLegalDocumentAction
import com.reals.backend.domain.UserStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.repository.UserLegalDocumentActionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

class AccountDeletionRetentionIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var userLegalDocumentActionRepository: UserLegalDocumentActionRepository

    @Test
    fun `deletion removes all ephemeral state and preserves counterpart Home invalidation`() {
        val setup = createConnectionInSchedulingPhase()
        createEphemeralState(setup.userAId, setup.connectionId)
        val counterpartVersionBefore = homeStatusService.getOrCreateStatus(setup.userBId).version

        userService.deleteUser(setup.userAId)

        val deletedUser = userRepository.findById(setup.userAId).orElseThrow()
        assertEquals(UserStatus.DELETED, deletedUser.status)
        assertNotNull(deletedUser.deletedAt)
        assertNotNull(deletedUser.deletionFinalizesAt)
        assertEphemeralStateAbsent(setup.userAId)
        assertTrue(
            homeStatusRepository.findById(setup.userBId).orElseThrow().version >
                counterpartVersionBefore
        )
    }

    @Test
    fun `deletion retains representative recovery safety legal and audit data`() {
        val setup = createMatchWithFirstChat(emailPrefix = "retention-boundary")
        val profile = profileRepository.findByUserId(setup.userAId)!!
        val photoIds = profilePhotoRepository.findByProfileId(profile.id).map { it.id }.sorted()
        val message = chatMessageRepository.save(
            ChatMessage(
                chatSessionId = setup.firstChatId,
                senderId = setup.userAId,
                content = "retained conversation content"
            )
        )
        val block = userBlockRepository.save(
            UserBlock(
                blockerUserId = setup.userAId,
                blockedUserId = setup.userBId,
                source = UserBlockSource.MANUAL
            )
        )
        val penalty = penaltyRepository.save(
            Penalty(
                userId = setup.userAId,
                reason = "retention boundary",
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = OffsetDateTime.now().plusDays(1)
            )
        )
        val report = safetyReportRepository.save(
            SafetyReport(
                reporterUserId = setup.userAId,
                reportedUserId = setup.userBId,
                chatId = setup.firstChatId,
                matchId = setup.matchId,
                contextType = SafetyReportContextType.CHAT,
                contextId = setup.firstChatId,
                reason = SafetyReportReason.OTHER,
                details = "retained safety report"
            )
        )
        val evidence = safetyReportEvidenceSnapshotRepository.save(
            SafetyReportEvidenceSnapshot(
                safetyReportId = report.id,
                chatId = setup.firstChatId,
                matchId = setup.matchId,
                messageCount = 1,
                transcriptSha256 = "a".repeat(64)
            )
        )
        val legalAction = userLegalDocumentActionRepository.save(
            UserLegalDocumentAction(
                userId = setup.userAId,
                documentType = LegalDocumentType.TERMS_OF_USE,
                documentVersion = "retention-test-v1",
                action = LegalDocumentAction.ACCEPTED
            )
        )
        val preExistingAudit = auditEventRepository.save(
            AuditEvent(
                eventType = AuditEventType.PROFILE_ACTIVATED,
                aggregateType = AuditAggregateType.PROFILE,
                aggregateId = profile.id,
                actorUserId = setup.userAId
            )
        )

        userService.deleteUser(setup.userAId)

        assertEquals(ProfileStatus.DRAFT, profileRepository.findById(profile.id).orElseThrow().status)
        assertEquals(photoIds, profilePhotoRepository.findByProfileId(profile.id).map { it.id }.sorted())
        assertTrue(chatMessageRepository.existsById(message.id))
        assertTrue(userBlockRepository.existsById(block.id))
        assertTrue(penaltyRepository.existsById(penalty.id))
        assertTrue(safetyReportRepository.existsById(report.id))
        assertTrue(safetyReportEvidenceSnapshotRepository.existsById(evidence.id))
        assertTrue(userLegalDocumentActionRepository.existsById(legalAction.id))
        assertTrue(auditEventRepository.existsById(preExistingAudit.id))
        assertTrue(
            auditEventRepository.findAll().any {
                it.eventType == AuditEventType.ACCOUNT_DELETION_REQUESTED &&
                    it.aggregateId == setup.userAId
            }
        )
    }

    @Test
    fun `reactivation does not restore ephemeral state and allows fresh Home and FCM state`() {
        val setup = createConnectionInSchedulingPhase()
        createEphemeralState(setup.userAId, setup.connectionId)
        val oldHomeVersion = homeStatusService.bump(setup.userAId, "retention_test").version
        val oldToken = pushDeviceTokenRepository.findByUserIdAndEnabledTrue(setup.userAId).single().token

        userService.deleteUser(setup.userAId)
        val reactivated = userService.reactivateUser(setup.userAId)

        assertEquals(UserStatus.ACTIVE, reactivated.status)
        assertEquals(ProfileStatus.DRAFT, profileRepository.findByUserId(setup.userAId)!!.status)
        assertEphemeralStateAbsent(setup.userAId)

        val recreatedHomeStatus = homeStatusService.getOrCreateStatus(setup.userAId)
        assertTrue(homeStatusRepository.existsById(setup.userAId))
        assertNotEquals(oldHomeVersion, recreatedHomeStatus.version)

        val newToken = pushDeviceTokenService.registerToken(
            userId = setup.userAId,
            token = "new-${UUID.randomUUID()}",
            platform = PushPlatform.ANDROID
        )
        assertTrue(pushDeviceTokenRepository.existsById(newToken.id))
        assertFalse(pushDeviceTokenRepository.findAll().any { it.token == oldToken })
    }

    private fun createEphemeralState(userId: UUID, connectionId: UUID) {
        matchmakingQueueRepository.save(
            MatchmakingQueueEntry(
                userId = userId,
                status = QueueStatus.WAITING,
                latitude = BUENOS_AIRES_LATITUDE,
                longitude = BUENOS_AIRES_LONGITUDE,
                accuracyMeters = 25
            )
        )
        lockRepository.save(
            ActiveEngagementLock(
                userId = userId,
                engagementId = UUID.randomUUID(),
                engagementType = EngagementType.MATCH
            )
        )
        pushDeviceTokenService.registerToken(
            userId = userId,
            token = "old-${UUID.randomUUID()}",
            platform = PushPlatform.ANDROID
        )
        pushNotificationDeliveryRepository.save(
            PushNotificationDelivery(
                userId = userId,
                notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
                aggregateId = connectionId,
                status = PushDeliveryStatus.SENT,
                providerMessageId = "provider-message"
            )
        )
        connectionHomeDismissalRepository.save(
            ConnectionHomeDismissal(
                userId = userId,
                connectionId = connectionId
            )
        )
        homeStatusService.bump(userId, "retention_test_setup")
    }

    private fun assertEphemeralStateAbsent(userId: UUID) {
        assertFalse(matchmakingQueueRepository.existsByUserId(userId))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userId, EngagementType.MATCH))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userId, EngagementType.CONNECTION))
        assertTrue(pushDeviceTokenRepository.findByUserIdAndEnabledTrue(userId).isEmpty())
        assertFalse(pushDeviceTokenRepository.findAll().any { it.userId == userId })
        assertFalse(pushNotificationDeliveryRepository.findAll().any { it.userId == userId })
        assertFalse(connectionHomeDismissalRepository.findAll().any { it.userId == userId })
        assertFalse(homeStatusRepository.existsById(userId))
    }
}
