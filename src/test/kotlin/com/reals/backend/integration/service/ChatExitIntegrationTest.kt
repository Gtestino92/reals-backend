package com.reals.backend.integration.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatExitRequest
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

class ChatExitIntegrationTest : BaseIT() {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `accept mutual cancellation closes first chat`() {
        val setup = createMatchWithFirstChat()

        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )

        val outcome =
            chatExitService.acceptMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                responderUserId = setup.userBId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.MUTUAL_CANCEL, outcome.chat.endedReason)
        assertEquals(ChatExitRequestStatus.ACCEPTED, outcome.exitRequest.status)
        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNotNull(chatService.findByIdOrThrow(setup.firstChatId).endedAt)
        assertFalse(outcome.penaltyApplied)
        assertNull(outcome.penalizedUserId)
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertNoActivePenalties(setup.userAId, setup.userBId)
    }

    @Test
    fun `reject mutual cancellation closes first chat`() {
        val setup = createMatchWithFirstChat()
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )

        val outcome =
            chatExitService.rejectMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                responderUserId = setup.userBId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.MUTUAL_CANCEL, outcome.chat.endedReason)
        assertEquals(ChatExitRequestStatus.REJECTED, outcome.exitRequest.status)
        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertFalse(outcome.penaltyApplied)
        assertNull(outcome.penalizedUserId)
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertNoActivePenalties(setup.userAId, setup.userBId)
    }

    @Test
    fun `timeout mutual cancellation closes first chat after timeout`() {
        val setup = createMatchWithFirstChat()
        val exitRequest =
            expired(
                chatExitService.requestMutualCancellation(
                    chatId = setup.firstChatId,
                    requesterUserId = setup.userAId
                )
            )

        val outcome =
            chatExitService.timeoutMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                userId = setup.userBId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.MUTUAL_CANCEL, outcome.chat.endedReason)
        assertEquals(ChatExitRequestStatus.TIMED_OUT, outcome.exitRequest.status)
        assertFalse(outcome.penaltyApplied)
        assertNull(outcome.penalizedUserId)
        assertNoMatchLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `timeout mutual cancellation can be called by requester without penalty`() {
        val setup = createMatchWithFirstChat()
        val exitRequest =
            expired(
                chatExitService.requestMutualCancellation(
                    chatId = setup.firstChatId,
                    requesterUserId = setup.userAId
                )
            )

        val outcome =
            chatExitService.timeoutMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                userId = setup.userAId
            )

        assertEquals(ChatExitRequestStatus.TIMED_OUT, outcome.exitRequest.status)
        assertFalse(outcome.penaltyApplied)
        assertNull(outcome.penalizedUserId)
        assertNoActivePenalties(setup.userAId, setup.userBId)
    }

    @Test
    fun `timeout mutual cancellation can be called by responder without penalty`() {
        val setup = createMatchWithFirstChat()
        val exitRequest =
            expired(
                chatExitService.requestMutualCancellation(
                    chatId = setup.firstChatId,
                    requesterUserId = setup.userAId
                )
            )

        val outcome =
            chatExitService.timeoutMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                userId = setup.userBId
            )

        assertEquals(ChatExitRequestStatus.TIMED_OUT, outcome.exitRequest.status)
        assertFalse(outcome.penaltyApplied)
        assertNull(outcome.penalizedUserId)
        assertNoActivePenalties(setup.userAId, setup.userBId)
    }

    @Test
    fun `timeout mutual cancellation fails before timeout`() {
        val setup = createMatchWithFirstChat()
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )

        val exception = assertThrows<DomainConflictException> {
            chatExitService.timeoutMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                userId = setup.userBId
            )
        }
        assertEquals(DomainErrorCode.CHAT_EXIT_REQUEST_NOT_AVAILABLE, exception.code)

        assertEquals(ChatStatus.ACTIVE, chatService.findByIdOrThrow(setup.firstChatId).status)
        assertEquals(
            ChatExitRequestStatus.PENDING,
            chatExitRequestRepository.findById(exitRequest.id).orElseThrow().status
        )
    }

    @Test
    fun `accept reject and timeout fail when request is not pending`() {
        val acceptedSetup = createMatchWithFirstChat("accepted-exit")
        val acceptedRequest =
            nonPendingRequest(
                setup = acceptedSetup,
                status = ChatExitRequestStatus.ACCEPTED
            )
        val acceptException = assertThrows<DomainConflictException> {
            chatExitService.acceptMutualCancellation(
                chatId = acceptedSetup.firstChatId,
                requestId = acceptedRequest.id,
                responderUserId = acceptedSetup.userBId
            )
        }
        assertEquals(DomainErrorCode.CHAT_EXIT_REQUEST_NOT_AVAILABLE, acceptException.code)

        val rejectedSetup = createMatchWithFirstChat("rejected-exit")
        val rejectedRequest =
            nonPendingRequest(
                setup = rejectedSetup,
                status = ChatExitRequestStatus.REJECTED
            )
        val rejectException = assertThrows<DomainConflictException> {
            chatExitService.rejectMutualCancellation(
                chatId = rejectedSetup.firstChatId,
                requestId = rejectedRequest.id,
                responderUserId = rejectedSetup.userBId
            )
        }
        assertEquals(DomainErrorCode.CHAT_EXIT_REQUEST_NOT_AVAILABLE, rejectException.code)

        val timedOutSetup = createMatchWithFirstChat("timed-out-exit")
        val timedOutRequest =
            nonPendingRequest(
                setup = timedOutSetup,
                status = ChatExitRequestStatus.TIMED_OUT
            )
        val timeoutException = assertThrows<DomainConflictException> {
            chatExitService.timeoutMutualCancellation(
                chatId = timedOutSetup.firstChatId,
                requestId = timedOutRequest.id,
                userId = timedOutSetup.userBId
            )
        }
        assertEquals(DomainErrorCode.CHAT_EXIT_REQUEST_NOT_AVAILABLE, timeoutException.code)
    }

    @Test
    fun `missing exit request returns stable not found code`() {
        val setup = createMatchWithFirstChat()

        val exception = assertThrows<DomainNotFoundException> {
            chatExitService.acceptMutualCancellation(
                chatId = setup.firstChatId,
                requestId = UUID.randomUUID(),
                responderUserId = setup.userBId
            )
        }
        assertEquals(DomainErrorCode.CHAT_EXIT_REQUEST_NOT_FOUND, exception.code)
    }

    @Test
    fun `requester cannot accept or reject own mutual cancellation`() {
        val acceptSetup = createMatchWithFirstChat("own-accept")
        val acceptRequest =
            chatExitService.requestMutualCancellation(
                chatId = acceptSetup.firstChatId,
                requesterUserId = acceptSetup.userAId
            )

        val acceptException = assertThrows<DomainConflictException> {
            chatExitService.acceptMutualCancellation(
                chatId = acceptSetup.firstChatId,
                requestId = acceptRequest.id,
                responderUserId = acceptSetup.userAId
            )
        }
        assertEquals(DomainErrorCode.CHAT_EXIT_REQUEST_NOT_AVAILABLE, acceptException.code)

        val rejectSetup = createMatchWithFirstChat("own-reject")
        val rejectRequest =
            chatExitService.requestMutualCancellation(
                chatId = rejectSetup.firstChatId,
                requesterUserId = rejectSetup.userAId
            )

        val rejectException = assertThrows<DomainConflictException> {
            chatExitService.rejectMutualCancellation(
                chatId = rejectSetup.firstChatId,
                requestId = rejectRequest.id,
                responderUserId = rejectSetup.userAId
            )
        }
        assertEquals(DomainErrorCode.CHAT_EXIT_REQUEST_NOT_AVAILABLE, rejectException.code)
    }

    @Test
    fun `non participant cannot resolve mutual cancellation`() {
        val setup = createMatchWithFirstChat()
        val stranger = userService.createUser("chat-exit-stranger-${UUID.randomUUID()}@example.com")
        val exitRequest =
            expired(
                chatExitService.requestMutualCancellation(
                    chatId = setup.firstChatId,
                    requesterUserId = setup.userAId
                )
            )

        assertThrows<AccessDeniedException> {
            chatExitService.acceptMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                responderUserId = stranger.id
            )
        }
        assertThrows<AccessDeniedException> {
            chatExitService.rejectMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                responderUserId = stranger.id
            )
        }
        assertThrows<AccessDeniedException> {
            chatExitService.timeoutMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                userId = stranger.id
            )
        }
    }

    @Test
    fun `unilateral first chat cancellation before minimum messages applies penalty`() {
        val setup = createMatchWithFirstChat()

        val outcome =
            chatExitService.cancelChatUnilaterally(
                chatId = setup.firstChatId,
                userId = setup.userAId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.UNILATERAL_CANCEL, outcome.chat.endedReason)
        assertEquals(ChatExitRequestType.UNILATERAL_CANCEL, outcome.exitRequest.type)
        assertEquals(ChatExitRequestStatus.ACCEPTED, outcome.exitRequest.status)
        assertTrue(outcome.penaltyApplied)
        assertEquals(setup.userAId, outcome.penalizedUserId)
        assertTrue(penaltyRepository.existsByUserIdAndActiveTrue(setup.userAId))
        assertNoMatchLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `unilateral first chat cancellation after minimum messages does not apply penalty`() {
        val setup = createMatchWithFirstChat()
        chatService.sendMessage(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            content = "Message before cancellation"
        )

        val outcome =
            chatExitService.cancelChatUnilaterally(
                chatId = setup.firstChatId,
                userId = setup.userAId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.UNILATERAL_CANCEL, outcome.chat.endedReason)
        assertEquals(ChatExitRequestType.UNILATERAL_CANCEL, outcome.exitRequest.type)
        assertEquals(ChatExitRequestStatus.ACCEPTED, outcome.exitRequest.status)
        assertFalse(outcome.penaltyApplied)
        assertNull(outcome.penalizedUserId)
        assertNoActivePenalties(setup.userAId, setup.userBId)
    }

    @Test
    fun `safety cancellation closes first chat and creates pending report without penalty`() {
        val setup = createMatchWithFirstChat()

        val outcome =
            chatExitService.cancelChatForSafety(
                chatId = setup.firstChatId,
                reporterUserId = setup.userAId,
                reason = ChatExitReason.INAPPROPRIATE_BEHAVIOR,
                details = "Reported inappropriate behavior"
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.SAFETY_REPORT, outcome.chat.endedReason)
        assertEquals(ChatExitRequestType.SAFETY_REPORT, outcome.exitRequest.type)
        assertEquals(ChatExitRequestStatus.ACCEPTED, outcome.exitRequest.status)
        assertFalse(outcome.penaltyApplied)
        assertNull(outcome.penalizedUserId)
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(setup.userAId))
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))

        val report = safetyReportRepository.findAll().single()
        assertEquals(SafetyReportStatus.PENDING, report.status)
        assertEquals(SafetyReportReason.INAPPROPRIATE_BEHAVIOR, report.reason)
        assertEquals("Reported inappropriate behavior", report.details)
        assertEquals(setup.userAId, report.reporterUserId)
        assertEquals(setup.userBId, report.reportedUserId)
        assertEquals(setup.firstChatId, report.chatId)
        assertEquals(setup.matchId, report.matchId)
        assertEquals(SafetyReportContextType.CHAT, report.contextType)
        assertEquals(setup.firstChatId, report.contextId)
        assertNull(report.connectionId)
        assertNull(report.penaltyId)

        val block = userBlockRepository.findByBlockerUserIdAndBlockedUserId(
            blockerUserId = setup.userAId,
            blockedUserId = setup.userBId
        ) ?: error("Expected safety report to create a user block")
        assertEquals(UserBlockSource.SAFETY_REPORT, block.source)
        assertEquals(report.id, block.sourceReportId)
        assertTrue(userBlockService.isBlockedPair(setup.userAId, setup.userBId))
        assertTrue(userBlockService.isBlockedPair(setup.userBId, setup.userAId))

        val repeatedBlock = userBlockService.blockUser(
            blockerUserId = setup.userAId,
            blockedUserId = setup.userBId,
            source = UserBlockSource.SAFETY_REPORT,
            sourceReportId = report.id
        )
        assertEquals(block.id, repeatedBlock.id)
        assertEquals(
            1,
            userBlockRepository.findAll()
                .count { it.blockerUserId == setup.userAId && it.blockedUserId == setup.userBId }
        )

        val snapshot = safetyReportEvidenceSnapshotRepository.findBySafetyReportId(report.id)
            ?: error("Expected safety report evidence snapshot")
        assertEquals(report.id, snapshot.safetyReportId)
        assertEquals(setup.firstChatId, snapshot.chatId)
        assertEquals(setup.matchId, snapshot.matchId)
        assertEquals(0, snapshot.messageCount)

        val reportAudit = auditEventRepository.findAll()
            .single {
                it.eventType == AuditEventType.SAFETY_REPORT_CREATED &&
                    it.aggregateType == AuditAggregateType.SAFETY_REPORT &&
                    it.aggregateId == report.id
            }
        assertEquals(setup.userAId, reportAudit.actorUserId)
        assertEquals(setup.userBId, reportAudit.targetUserId)
        val metadata = objectMapper.readTree(reportAudit.metadataJson)

        assertEquals("CHAT", metadata.get("contextType").asString())
        assertEquals(setup.firstChatId.toString(), metadata.get("contextId").asString())
        assertEquals("INAPPROPRIATE_BEHAVIOR", metadata.get("reason").asString())
        assertEquals("PENDING", metadata.get("status").asString())
        assertFalse(reportAudit.metadataJson!!.contains("Reported inappropriate behavior"))
        val chatEndedAudit = auditEventRepository.findAll()
            .single {
                it.eventType == AuditEventType.CHAT_ENDED &&
                    it.aggregateType == AuditAggregateType.CHAT &&
                    it.aggregateId == setup.firstChatId
            }
        assertEquals(setup.userAId, chatEndedAudit.actorUserId)
        assertTrue(chatEndedAudit.metadataJson!!.contains("SAFETY_REPORT"))

        assertEquals(
            1,
            auditEventRepository.findAll()
                .count {
                    it.eventType == AuditEventType.USER_BLOCK_CREATED &&
                        it.aggregateType == AuditAggregateType.USER_BLOCK &&
                        it.aggregateId == block.id
                }
        )
    }

    @Test
    fun `safety cancellation requires details`() {
        val setup = createMatchWithFirstChat()

        val nullDetailsException = assertThrows<DomainBadRequestException> {
            chatExitService.cancelChatForSafety(
                chatId = setup.firstChatId,
                reporterUserId = setup.userAId,
                details = null
            )
        }
        assertEquals(DomainErrorCode.CHAT_MESSAGE_INVALID, nullDetailsException.code)

        val blankDetailsException = assertThrows<DomainBadRequestException> {
            chatExitService.cancelChatForSafety(
                chatId = setup.firstChatId,
                reporterUserId = setup.userAId,
                details = "   "
            )
        }
        assertEquals(DomainErrorCode.CHAT_MESSAGE_INVALID, blankDetailsException.code)

        assertEquals(ChatStatus.ACTIVE, chatService.findByIdOrThrow(setup.firstChatId).status)
    }

    @Test
    fun `accept mutual cancellation closes second chat connection`() {
        val setup = createActiveSecondChat()
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.secondChatId,
                requesterUserId = setup.userAId
            )

        val outcome =
            chatExitService.acceptMutualCancellation(
                chatId = setup.secondChatId,
                requestId = exitRequest.id,
                responderUserId = setup.userBId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.MUTUAL_CANCEL, outcome.chat.endedReason)
        assertEquals(ChatExitRequestStatus.ACCEPTED, outcome.exitRequest.status)
        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `reject mutual cancellation closes second chat connection`() {
        val setup = createActiveSecondChat()
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.secondChatId,
                requesterUserId = setup.userAId
            )

        val outcome =
            chatExitService.rejectMutualCancellation(
                chatId = setup.secondChatId,
                requestId = exitRequest.id,
                responderUserId = setup.userBId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.MUTUAL_CANCEL, outcome.chat.endedReason)
        assertEquals(ChatExitRequestStatus.REJECTED, outcome.exitRequest.status)
        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `timeout mutual cancellation closes second chat connection`() {
        val setup = createActiveSecondChat()
        val exitRequest =
            expired(
                chatExitService.requestMutualCancellation(
                    chatId = setup.secondChatId,
                    requesterUserId = setup.userAId
                )
            )

        val outcome =
            chatExitService.timeoutMutualCancellation(
                chatId = setup.secondChatId,
                requestId = exitRequest.id,
                userId = setup.userBId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.MUTUAL_CANCEL, outcome.chat.endedReason)
        assertEquals(ChatExitRequestStatus.TIMED_OUT, outcome.exitRequest.status)
        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertFalse(outcome.penaltyApplied)
        assertNull(outcome.penalizedUserId)
    }

    @Test
    fun `second chat exit fails when connection is not in second chat phase`() {
        val setup = createActiveSecondChat()
        val connection = connectionService.findByIdOrThrow(setup.connectionId)

        connection.state = ConnectionState.SECOND_CHAT_SCHEDULED
        connectionRepository.save(connection)

        val exception = assertThrows<DomainConflictException> {
            chatExitService.requestMutualCancellation(
                chatId = setup.secondChatId,
                requesterUserId = setup.userAId
            )
        }
        assertEquals(DomainErrorCode.CHAT_NOT_AVAILABLE, exception.code)
    }

    @Test
    fun `second chat exit fails for non participant`() {
        val setup = createActiveSecondChat()
        val stranger = userService.createUser("second-chat-stranger-${UUID.randomUUID()}@example.com")

        assertThrows<AccessDeniedException> {
            chatExitService.requestMutualCancellation(
                chatId = setup.secondChatId,
                requesterUserId = stranger.id
            )
        }
    }

    @Test
    fun `unilateral second chat cancellation before minimum messages applies penalty`() {
        val setup = createActiveSecondChat()

        val outcome =
            chatExitService.cancelChatUnilaterally(
                chatId = setup.secondChatId,
                userId = setup.userAId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ChatEndReason.UNILATERAL_CANCEL, outcome.chat.endedReason)
        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(setup.userAId, outcome.penalizedUserId)
        assertTrue(penaltyRepository.existsByUserIdAndActiveTrue(setup.userAId))
    }

    private fun expired(exitRequest: ChatExitRequest): ChatExitRequest {
        exitRequest.createdAt = OffsetDateTime.now().minusSeconds(30)
        return chatExitRequestRepository.save(exitRequest)
    }

    private fun nonPendingRequest(
        setup: MatchFixture,
        status: ChatExitRequestStatus
    ): ChatExitRequest {
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )
        exitRequest.status = status
        exitRequest.resolvedAt = OffsetDateTime.now()
        return chatExitRequestRepository.save(exitRequest)
    }
}
