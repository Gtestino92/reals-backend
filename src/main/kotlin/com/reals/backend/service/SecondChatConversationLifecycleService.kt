package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.domain.SecondChatResolutionRequest
import com.reals.backend.domain.SecondChatResolutionRequestStatus
import com.reals.backend.domain.SecondChatResolutionRequestType
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.SecondChatParticipationRepository
import com.reals.backend.repository.SecondChatResolutionRequestRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.reliability.UserReliabilityScoreService
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class SecondChatConversationLifecycleService(
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val connectionRepository: ConnectionRepository,
    private val connectionService: ConnectionService,
    private val participationRepository: SecondChatParticipationRepository,
    private val resolutionRequestRepository: SecondChatResolutionRequestRepository,
    private val userBlockService: UserBlockService,
    private val userReliabilityScoreService: UserReliabilityScoreService,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,

    @param:Value("\${chat.second-chat.read-only-retention-minutes:1440}")
    private val readOnlyRetentionMinutes: Long,

    @param:Value("\${chat.second-chat.mutual-completion.minimum-conversation-minutes:10}")
    private val mutualCompletionMinimumConversationMinutes: Long,

    @param:Value("\${chat.second-chat.mutual-completion.request-countdown-seconds:60}")
    private val mutualCompletionRequestCountdownSeconds: Long,

    @param:Value("\${chat.second-chat.mutual-completion.requester-cooldown-seconds:60}")
    private val mutualCompletionRequesterCooldownSeconds: Long,

    @param:Value("\${chat.second-chat.inactivity.claimable-after-minutes:5}")
    private val inactivityClaimableAfterMinutes: Long,

    @param:Value("\${chat.second-chat.inactivity.automatic-close-after-minutes:10}")
    private val inactivityAutomaticCloseAfterMinutes: Long,

    @param:Value("\${chat.second-chat.inactivity.claim-countdown-seconds:60}")
    private val inactivityClaimCountdownSeconds: Long,

    @param:Value("\${chat.second-chat.initial-silence.automatic-close-after-minutes:10}")
    private val initialSilenceAutomaticCloseAfterMinutes: Long
) {

    enum class CompletionDecision {
        ACCEPTED,
        REJECTED
    }

    data class RequestResult(
        val request: SecondChatResolutionRequest?,
        val status: SecondChatConversationStatus,
        val created: Boolean
    )

    sealed interface CompletionDecisionResult {
        data class Applied(
            val request: SecondChatResolutionRequest,
            val status: SecondChatConversationStatus
        ) : CompletionDecisionResult

        data class Rejected(
            val code: DomainErrorCode,
            val message: String,
            val status: SecondChatConversationStatus
        ) : CompletionDecisionResult
    }

    sealed interface SecondChatMessageResult {
        data class Continue(
            val chat: Chat
        ) : SecondChatMessageResult

        data class RejectedAfterResolution(
            val code: DomainErrorCode,
            val message: String
        ) : SecondChatMessageResult
    }

    data class SecondChatConversationStatus(
        val activeResolutionRequest: SecondChatResolutionRequest?,
        val chatStatus: ChatStatus?,
        val endedReason: ChatEndReason?,
        val endedAt: OffsetDateTime?,
        val readOnlyUntil: OffsetDateTime?,
        val mutualCompletionEligibleAt: OffsetDateTime?,
        val canRequestMutualCompletion: Boolean,
        val mutualCompletionCooldownUntil: OffsetDateTime?,
        val inactivityClaimableAt: OffsetDateTime?,
        val inactivityClosesAt: OffsetDateTime?,
        val canClaimPartnerInactivity: Boolean,
        val mustRespondToPartner: Boolean,
        val lastMessageAt: OffsetDateTime?,
        val lastMessageSenderId: UUID?
    )

    fun createMutualCompletionRequest(
        connectionId: UUID,
        requesterUserId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): RequestResult {
        val context = lockedActiveConversationContext(connectionId, requesterUserId)
        val chat = context.chat
        resolveDueNonTerminalCompletionRequest(chat, now)
        resolveDueInitialSilenceOrInactivity(chat, now)?.let {
            return RequestResult(
                request = null,
                status = buildStatus(context.connection, chat, requesterUserId, now),
                created = false
            )
        }

        validateMutualCompletionEligible(context, requesterUserId, now)

        findPendingRequestForUpdate(connectionId)?.let { pending ->
            if (
                pending.type == SecondChatResolutionRequestType.MUTUAL_COMPLETION &&
                pending.requesterUserId == requesterUserId
            ) {
                return RequestResult(
                    request = pending,
                    status = buildStatus(context.connection, chat, requesterUserId, now),
                    created = false
                )
            }
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_COMPLETION_REQUEST_ALREADY_PENDING,
                message = "A second-chat resolution request is already pending for connection $connectionId"
            )
        }

        val request =
            resolutionRequestRepository.saveAndFlush(
                SecondChatResolutionRequest(
                    connectionId = connectionId,
                    chatId = chat.id,
                    requesterUserId = requesterUserId,
                    responderUserId = context.connection.partnerUserId(requesterUserId),
                    type = SecondChatResolutionRequestType.MUTUAL_COMPLETION,
                    status = SecondChatResolutionRequestStatus.PENDING,
                    createdAt = now,
                    expiresAt = minOf(now.plusSeconds(mutualCompletionRequestCountdownSeconds), chat.timeoutAt)
                )
            )
        bumpBoth(context.connection, "second_chat_completion_request_created")
        return RequestResult(
            request = request,
            status = buildStatus(context.connection, chat, requesterUserId, now),
            created = true
        )
    }

    fun decideMutualCompletion(
        connectionId: UUID,
        requestId: UUID,
        responderUserId: UUID,
        decision: CompletionDecision,
        now: OffsetDateTime = OffsetDateTime.now()
    ): CompletionDecisionResult {
        val context = lockedActiveConversationContext(connectionId, responderUserId)
        val chat = context.chat
        val request =
            resolutionRequestRepository.findByIdForUpdate(requestId)
                ?: throw DomainNotFoundException(
                    code = DomainErrorCode.SECOND_CHAT_COMPLETION_REQUEST_NOT_FOUND,
                    message = "Second-chat completion request was not found"
                )

        if (
            request.connectionId != connectionId ||
            request.chatId != chat.id ||
            request.type != SecondChatResolutionRequestType.MUTUAL_COMPLETION ||
            request.status != SecondChatResolutionRequestStatus.PENDING ||
            request.responderUserId != responderUserId
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_COMPLETION_REQUEST_NOT_ACTIONABLE,
                message = "Second-chat completion request is not actionable"
            )
        }

        if (!request.expiresAt.isAfter(now)) {
            request.status = SecondChatResolutionRequestStatus.TIMED_OUT
            request.resolvedAt = now
            resolutionRequestRepository.save(request)
            bumpBoth(context.connection, "second_chat_completion_request_timed_out")
            return CompletionDecisionResult.Rejected(
                code = DomainErrorCode.SECOND_CHAT_COMPLETION_REQUEST_NOT_ACTIONABLE,
                message = "Second-chat completion request expired at ${request.expiresAt}",
                status = buildStatus(context.connection, chat, responderUserId, now)
            )
        }

        request.resolvedAt = now
        if (decision == CompletionDecision.REJECTED) {
            request.status = SecondChatResolutionRequestStatus.REJECTED
            resolutionRequestRepository.save(request)
            bumpBoth(context.connection, "second_chat_completion_request_rejected")
            return CompletionDecisionResult.Applied(
                request = request,
                status = buildStatus(context.connection, chat, responderUserId, now)
            )
        }

        request.status = SecondChatResolutionRequestStatus.ACCEPTED
        resolutionRequestRepository.save(request)
        finishSecondChat(
            connection = context.connection,
            chat = chat,
            status = ChatStatus.FINISHED,
            endedReason = ChatEndReason.SECOND_CHAT_MUTUAL_COMPLETION,
            now = now,
            reliabilityRecorder = {
                listOf(context.connection.userAId, context.connection.userBId).forEach { userId ->
                    userReliabilityScoreService.recordEvent(
                        userId = userId,
                        eventType = UserReliabilityEventType.SECOND_CHAT_MUTUAL_COMPLETION,
                        relatedMatchId = chat.matchId,
                        relatedConnectionId = connectionId,
                        relatedChatId = chat.id,
                        occurredAt = now
                    )
                }
            }
        )
        return CompletionDecisionResult.Applied(
            request = request,
            status = buildStatus(context.connection, chat, responderUserId, now)
        )
    }

    fun createPartnerInactivityClaim(
        connectionId: UUID,
        requesterUserId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): RequestResult {
        val context = lockedActiveConversationContext(connectionId, requesterUserId)
        val chat = context.chat
        resolveDueNonTerminalCompletionRequest(chat, now)
        resolveDueInitialSilenceOrInactivity(chat, now)?.let {
            return RequestResult(
                request = null,
                status = buildStatus(context.connection, chat, requesterUserId, now),
                created = false
            )
        }
        validateInactivityClaimEligible(context, requesterUserId, now)

        findPendingRequestForUpdate(connectionId)?.let { pending ->
            if (
                pending.type == SecondChatResolutionRequestType.PARTNER_INACTIVITY &&
                pending.requesterUserId == requesterUserId
            ) {
                return RequestResult(
                    request = pending,
                    status = buildStatus(context.connection, chat, requesterUserId, now),
                    created = false
                )
            }
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_INACTIVITY_CLAIM_ALREADY_PENDING,
                message = "A second-chat resolution request is already pending for connection $connectionId"
            )
        }

        val latestMessage = latestMessage(chat)
            ?: throw inactivityClaimNotAvailable(connectionId)
        val lastMessageAt = chat.lastMessageAt
            ?: throw inactivityClaimNotAvailable(connectionId)
        val request =
            resolutionRequestRepository.saveAndFlush(
                SecondChatResolutionRequest(
                    connectionId = connectionId,
                    chatId = chat.id,
                    referenceMessageId = latestMessage.id,
                    requesterUserId = requesterUserId,
                    responderUserId = context.connection.partnerUserId(requesterUserId),
                    type = SecondChatResolutionRequestType.PARTNER_INACTIVITY,
                    status = SecondChatResolutionRequestStatus.PENDING,
                    createdAt = now,
                    expiresAt = minOf(
                        now.plusSeconds(inactivityClaimCountdownSeconds),
                        lastMessageAt.plusMinutes(inactivityAutomaticCloseAfterMinutes),
                        chat.timeoutAt
                    )
                )
            )
        bumpBoth(context.connection, "second_chat_inactivity_claim_created")
        return RequestResult(
            request = request,
            status = buildStatus(context.connection, chat, requesterUserId, now),
            created = true
        )
    }

    fun beforeSecondChatMessage(
        chat: Chat,
        senderId: UUID,
        now: OffsetDateTime
    ): SecondChatMessageResult {
        if (chat.chatType != ChatType.SECOND_CHAT) {
            return SecondChatMessageResult.Continue(chat)
        }
        val connectionId = chat.connectionId ?: return rejectedConversationResolved(chat.id)
        val connection = connectionRepository.findById(connectionId).orElse(null)
            ?: return rejectedConversationResolved(chat.id)
        val pending = findPendingRequestForUpdate(connectionId)

        if (pending?.type == SecondChatResolutionRequestType.PARTNER_NO_SHOW) {
            if (!pending.expiresAt.isAfter(now)) {
                return SecondChatMessageResult.RejectedAfterResolution(
                    code = DomainErrorCode.SECOND_CHAT_ALREADY_RESOLVED,
                    message = "Second chat for connection $connectionId is already resolved by no-show"
                )
            }
        }

        if (pending?.type == SecondChatResolutionRequestType.PARTNER_INACTIVITY) {
            if (!pending.expiresAt.isAfter(now)) {
                resolvePartnerInactivityClaimLocked(connection, chat, pending, now)
                return rejectedConversationResolved(chat.id)
            }
            cancelRequest(pending, now)
        }

        if (pending?.type == SecondChatResolutionRequestType.MUTUAL_COMPLETION) {
            if (!pending.expiresAt.isAfter(now)) {
                pending.status = SecondChatResolutionRequestStatus.TIMED_OUT
            } else {
                pending.status = SecondChatResolutionRequestStatus.CANCELLED
            }
            pending.resolvedAt = now
            resolutionRequestRepository.save(pending)
        }

        resolveDueInitialSilenceOrInactivity(chat, now)?.let {
            return rejectedConversationResolved(chat.id)
        }

        return SecondChatMessageResult.Continue(chat)
    }

    fun findExpiredMutualCompletionRequestIds(now: OffsetDateTime, limit: Int): List<UUID> =
        resolutionRequestRepository.findExpiredPendingRequestIdsByType(
            now = now,
            type = SecondChatResolutionRequestType.MUTUAL_COMPLETION,
            pageable = PageRequest.of(0, limit)
        )

    fun processExpiredMutualCompletionRequest(requestId: UUID, now: OffsetDateTime = OffsetDateTime.now()): Boolean {
        val snapshot = resolutionRequestRepository.findById(requestId).orElse(null) ?: return false
        val chat = snapshot.chatId?.let { chatRepository.findByIdForUpdate(it) } ?: return false
        val request = resolutionRequestRepository.findByIdForUpdate(requestId) ?: return false
        if (
            request.type != SecondChatResolutionRequestType.MUTUAL_COMPLETION ||
            request.status != SecondChatResolutionRequestStatus.PENDING ||
            request.expiresAt.isAfter(now)
        ) {
            return false
        }
        request.status = SecondChatResolutionRequestStatus.TIMED_OUT
        request.resolvedAt = now
        resolutionRequestRepository.save(request)
        chat.connectionId?.let { connectionId ->
            connectionRepository.findById(connectionId).orElse(null)?.let {
                bumpBoth(it, "second_chat_completion_request_timed_out")
            }
        }
        return true
    }

    fun findExpiredPartnerInactivityClaimIds(now: OffsetDateTime, limit: Int): List<UUID> =
        resolutionRequestRepository.findExpiredPendingRequestIdsByType(
            now = now,
            type = SecondChatResolutionRequestType.PARTNER_INACTIVITY,
            pageable = PageRequest.of(0, limit)
        )

    fun processExpiredPartnerInactivityClaim(requestId: UUID, now: OffsetDateTime = OffsetDateTime.now()): Boolean {
        val snapshot = resolutionRequestRepository.findById(requestId).orElse(null) ?: return false
        val chat = snapshot.chatId?.let { chatRepository.findByIdForUpdate(it) } ?: return false
        val request = resolutionRequestRepository.findByIdForUpdate(requestId) ?: return false
        val connection = chat.connectionId?.let { connectionRepository.findById(it).orElse(null) } ?: return false
        if (
            request.type != SecondChatResolutionRequestType.PARTNER_INACTIVITY ||
            request.status != SecondChatResolutionRequestStatus.PENDING ||
            request.expiresAt.isAfter(now)
        ) {
            return false
        }
        return resolvePartnerInactivityClaimLocked(connection, chat, request, now)
    }

    fun findInitialSilenceDueChatIds(now: OffsetDateTime, limit: Int): List<UUID> =
        chatRepository.findInitialSilenceDueSecondChatIds(
            dueBefore = now.minusMinutes(initialSilenceAutomaticCloseAfterMinutes),
            pageable = PageRequest.of(0, limit)
        )

    fun processInitialSilence(chatId: UUID, now: OffsetDateTime = OffsetDateTime.now()): Boolean {
        val chat = chatRepository.findByIdForUpdate(chatId) ?: return false
        if (chat.status != ChatStatus.ACTIVE || chat.chatType != ChatType.SECOND_CHAT || chat.lastMessageAt != null) {
            return false
        }
        val conversationStartedAt = chat.conversationStartedAt ?: return false
        if (conversationStartedAt.plusMinutes(initialSilenceAutomaticCloseAfterMinutes).isAfter(now)) {
            return false
        }
        val connection = chat.connectionId?.let { connectionRepository.findById(it).orElse(null) } ?: return false
        finishInitialSilence(connection, chat, now)
        return true
    }

    fun findAutomaticInactivityDueChatIds(now: OffsetDateTime, limit: Int): List<UUID> =
        chatRepository.findAutomaticInactivityDueSecondChatIds(
            dueBefore = now.minusMinutes(inactivityAutomaticCloseAfterMinutes),
            pageable = PageRequest.of(0, limit)
        )

    fun processAutomaticInactivity(chatId: UUID, now: OffsetDateTime = OffsetDateTime.now()): Boolean {
        val chat = chatRepository.findByIdForUpdate(chatId) ?: return false
        if (
            chat.status != ChatStatus.ACTIVE ||
            chat.chatType != ChatType.SECOND_CHAT ||
            chat.lastMessageAt == null ||
            chat.lastMessageSenderId == null
        ) {
            return false
        }
        if (chat.lastMessageAt!!.plusMinutes(inactivityAutomaticCloseAfterMinutes).isAfter(now)) {
            return false
        }
        val connection = chat.connectionId?.let { connectionRepository.findById(it).orElse(null) } ?: return false
        resolveDueCompletionBeforeTerminal(connection.id, now)
        findPendingRequestForUpdate(connection.id)
            ?.takeIf { it.type == SecondChatResolutionRequestType.PARTNER_INACTIVITY }
            ?.let { return resolvePartnerInactivityClaimLocked(connection, chat, it, now) }
        finishPartnerInactivity(connection, chat, connection.partnerUserId(chat.lastMessageSenderId!!), now)
        return true
    }

    @Transactional(readOnly = true)
    fun buildStatus(
        connection: Connection,
        chat: Chat?,
        userId: UUID,
        now: OffsetDateTime
    ): SecondChatConversationStatus {
        if (chat == null || chat.chatType != ChatType.SECOND_CHAT) {
            return emptyStatus()
        }
        val activeRequest =
            resolutionRequestRepository.findByConnectionIdAndStatusOrderByCreatedAtDesc(
                connectionId = connection.id,
                status = SecondChatResolutionRequestStatus.PENDING
            ).firstOrNull()
        val conversationStartedAt = chat.conversationStartedAt
        val mutualEligibleAt = conversationStartedAt?.plusMinutes(mutualCompletionMinimumConversationMinutes)
        val latest = latestMessage(chat)
        val lastMessageAt = chat.lastMessageAt
        val inactivityClaimableAt = lastMessageAt?.plusMinutes(inactivityClaimableAfterMinutes)
        val inactivityClosesAt = lastMessageAt?.plusMinutes(inactivityAutomaticCloseAfterMinutes)
        val myMustRespond = chat.lastMessageSenderId != null && chat.lastMessageSenderId != userId
        val cooldownUntil = mutualCompletionCooldownUntil(connection.id, userId)
        val terminal = chat.status != ChatStatus.ACTIVE || !chat.timeoutAt.isAfter(now)
        val pendingExpired = activeRequest != null && !activeRequest.expiresAt.isAfter(now)
        val bothMessaged = listOf(connection.userAId, connection.userBId).all {
            chatMessageRepository.existsByChatSessionIdAndSenderId(chat.id, it)
        }

        return SecondChatConversationStatus(
            activeResolutionRequest = activeRequest,
            chatStatus = chat.status,
            endedReason = chat.endedReason,
            endedAt = chat.endedAt,
            readOnlyUntil = chat.readOnlyUntil,
            mutualCompletionEligibleAt = mutualEligibleAt,
            canRequestMutualCompletion = !terminal &&
                !pendingExpired &&
                activeRequest == null &&
                mutualEligibleAt != null &&
                !now.isBefore(mutualEligibleAt) &&
                bothMessaged &&
                (cooldownUntil == null || !now.isBefore(cooldownUntil)),
            mutualCompletionCooldownUntil = cooldownUntil?.takeIf { it.isAfter(now) },
            inactivityClaimableAt = inactivityClaimableAt,
            inactivityClosesAt = inactivityClosesAt,
            canClaimPartnerInactivity = !terminal &&
                !pendingExpired &&
                activeRequest == null &&
                latest != null &&
                latest.senderId == userId &&
                inactivityClaimableAt != null &&
                !now.isBefore(inactivityClaimableAt) &&
                inactivityClosesAt != null &&
                now.isBefore(inactivityClosesAt),
            mustRespondToPartner = !terminal && myMustRespond,
            lastMessageAt = chat.lastMessageAt,
            lastMessageSenderId = chat.lastMessageSenderId
        )
    }

    private fun validateMutualCompletionEligible(
        context: SecondChatConversationContext,
        requesterUserId: UUID,
        now: OffsetDateTime
    ) {
        val chat = context.chat
        val conversationStartedAt = chat.conversationStartedAt
            ?: throw completionNotAvailable(context.connection.id)
        if (now.isBefore(conversationStartedAt.plusMinutes(mutualCompletionMinimumConversationMinutes))) {
            throw completionNotAvailable(context.connection.id)
        }
        if (!chat.timeoutAt.isAfter(now)) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_EXPIRED,
                message = "Second chat for connection ${context.connection.id} expired at ${chat.timeoutAt}"
            )
        }
        val bothJoined = participationRepository.findByConnectionId(context.connection.id)
            .count { it.attendanceStatus == SecondChatAttendanceStatus.ON_TIME || it.attendanceStatus == SecondChatAttendanceStatus.LATE } == 2
        if (!bothJoined) {
            throw completionNotAvailable(context.connection.id)
        }
        if (
            !chatMessageRepository.existsByChatSessionIdAndSenderId(chat.id, context.connection.userAId) ||
            !chatMessageRepository.existsByChatSessionIdAndSenderId(chat.id, context.connection.userBId)
        ) {
            throw completionNotAvailable(context.connection.id)
        }
        mutualCompletionCooldownUntil(context.connection.id, requesterUserId)?.let { cooldownUntil ->
            if (now.isBefore(cooldownUntil)) {
                throw DomainConflictException(
                    code = DomainErrorCode.SECOND_CHAT_COMPLETION_REQUEST_COOLDOWN,
                    message = "Second-chat completion request cooldown is active until $cooldownUntil"
                )
            }
        }
        userBlockService.requirePairNotBlocked(context.connection.userAId, context.connection.userBId)
    }

    private fun validateInactivityClaimEligible(
        context: SecondChatConversationContext,
        requesterUserId: UUID,
        now: OffsetDateTime
    ) {
        val chat = context.chat
        if (chat.conversationStartedAt == null || chat.lastMessageAt == null || chat.lastMessageSenderId == null) {
            throw inactivityClaimNotAvailable(context.connection.id)
        }
        val latestMessage = latestMessage(chat) ?: throw inactivityClaimNotAvailable(context.connection.id)
        if (latestMessage.senderId != requesterUserId || chat.lastMessageSenderId != requesterUserId) {
            throw inactivityClaimNotAvailable(context.connection.id)
        }
        val claimableAt = chat.lastMessageAt!!.plusMinutes(inactivityClaimableAfterMinutes)
        val closesAt = chat.lastMessageAt!!.plusMinutes(inactivityAutomaticCloseAfterMinutes)
        if (now.isBefore(claimableAt) || !now.isBefore(closesAt) || !chat.timeoutAt.isAfter(now)) {
            throw inactivityClaimNotAvailable(context.connection.id)
        }
    }

    private fun lockedActiveConversationContext(
        connectionId: UUID,
        userId: UUID
    ): SecondChatConversationContext {
        val chat = chatRepository.findByConnectionIdAndChatTypeForUpdate(connectionId, ChatType.SECOND_CHAT)
            ?: throw completionNotAvailable(connectionId)
        if (chat.status != ChatStatus.ACTIVE || chat.chatType != ChatType.SECOND_CHAT) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_CONVERSATION_ALREADY_RESOLVED,
                message = "Second chat for connection $connectionId is already resolved"
            )
        }
        val connection = connectionService.findByIdForUserOrThrow(connectionId, userId)
        if (chat.connectionId != connection.id) {
            throw completionNotAvailable(connectionId)
        }
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)
        return SecondChatConversationContext(connection, chat)
    }

    private fun resolveDueInitialSilenceOrInactivity(
        chat: Chat,
        now: OffsetDateTime
    ): Boolean? {
        if (chat.status != ChatStatus.ACTIVE || chat.chatType != ChatType.SECOND_CHAT) {
            return null
        }
        val connection = chat.connectionId?.let { connectionRepository.findById(it).orElse(null) } ?: return null
        if (chat.lastMessageAt == null) {
            val conversationStartedAt = chat.conversationStartedAt ?: return null
            if (!conversationStartedAt.plusMinutes(initialSilenceAutomaticCloseAfterMinutes).isAfter(now)) {
                finishInitialSilence(connection, chat, now)
                return true
            }
            return null
        }
        val lastSenderId = chat.lastMessageSenderId ?: return null
        if (!chat.lastMessageAt!!.plusMinutes(inactivityAutomaticCloseAfterMinutes).isAfter(now)) {
            resolveDueCompletionBeforeTerminal(connection.id, now)
            findPendingRequestForUpdate(connection.id)
                ?.takeIf { it.type == SecondChatResolutionRequestType.PARTNER_INACTIVITY }
                ?.let {
                    resolvePartnerInactivityClaimLocked(connection, chat, it, now)
                    return true
                }
            finishPartnerInactivity(connection, chat, connection.partnerUserId(lastSenderId), now)
            return true
        }
        return null
    }

    private fun resolveDueCompletionBeforeTerminal(connectionId: UUID, now: OffsetDateTime) {
        val pending = findPendingRequestForUpdate(connectionId) ?: return
        if (pending.type != SecondChatResolutionRequestType.MUTUAL_COMPLETION) {
            return
        }
        pending.status =
            if (!pending.expiresAt.isAfter(now)) {
                SecondChatResolutionRequestStatus.TIMED_OUT
            } else {
                SecondChatResolutionRequestStatus.CANCELLED
            }
        pending.resolvedAt = now
        resolutionRequestRepository.save(pending)
    }

    private fun resolveDueNonTerminalCompletionRequest(chat: Chat, now: OffsetDateTime) {
        val connectionId = chat.connectionId ?: return
        val pending = findPendingRequestForUpdate(connectionId) ?: return
        if (
            pending.type == SecondChatResolutionRequestType.MUTUAL_COMPLETION &&
            !pending.expiresAt.isAfter(now)
        ) {
            pending.status = SecondChatResolutionRequestStatus.TIMED_OUT
            pending.resolvedAt = now
            resolutionRequestRepository.save(pending)
        }
    }

    private fun resolvePartnerInactivityClaimLocked(
        connection: Connection,
        chat: Chat,
        request: SecondChatResolutionRequest,
        now: OffsetDateTime
    ): Boolean {
        if (
            request.status != SecondChatResolutionRequestStatus.PENDING ||
            request.type != SecondChatResolutionRequestType.PARTNER_INACTIVITY ||
            request.chatId != chat.id ||
            request.referenceMessageId == null
        ) {
            return false
        }
        val referenceMessage = chatMessageRepository.findById(request.referenceMessageId!!).orElse(null) ?: return false
        if (
            referenceMessage.chatSessionId != chat.id ||
            referenceMessage.senderId != request.requesterUserId ||
            request.responderUserId != connection.partnerUserId(request.requesterUserId)
        ) {
            request.status = SecondChatResolutionRequestStatus.CANCELLED
            request.resolvedAt = now
            resolutionRequestRepository.save(request)
            return false
        }
        val latestMessage = latestMessage(chat)
        if (
            latestMessage?.id != referenceMessage.id ||
            chat.lastMessageSenderId != request.requesterUserId
        ) {
            request.status = SecondChatResolutionRequestStatus.CANCELLED
            request.resolvedAt = now
            resolutionRequestRepository.save(request)
            return false
        }
        request.status = SecondChatResolutionRequestStatus.COMPLETED
        request.resolvedAt = now
        resolutionRequestRepository.save(request)
        finishPartnerInactivity(connection, chat, request.responderUserId, now)
        return true
    }

    private fun finishInitialSilence(
        connection: Connection,
        chat: Chat,
        now: OffsetDateTime
    ) {
        finishSecondChat(
            connection = connection,
            chat = chat,
            status = ChatStatus.ABANDONED,
            endedReason = ChatEndReason.SECOND_CHAT_NO_CONVERSATION_STARTED,
            now = now,
            reliabilityRecorder = {
                listOf(connection.userAId, connection.userBId).forEach { userId ->
                    userReliabilityScoreService.recordEvent(
                        userId = userId,
                        eventType = UserReliabilityEventType.SECOND_CHAT_NO_CONVERSATION_STARTED,
                        relatedMatchId = chat.matchId,
                        relatedConnectionId = connection.id,
                        relatedChatId = chat.id,
                        occurredAt = now
                    )
                }
            }
        )
    }

    private fun finishPartnerInactivity(
        connection: Connection,
        chat: Chat,
        abandonedUserId: UUID,
        now: OffsetDateTime
    ) {
        finishSecondChat(
            connection = connection,
            chat = chat,
            status = ChatStatus.ABANDONED,
            endedReason = ChatEndReason.SECOND_CHAT_PARTNER_INACTIVITY,
            now = now,
            reliabilityRecorder = {
                userReliabilityScoreService.recordEvent(
                    userId = abandonedUserId,
                    eventType = UserReliabilityEventType.SECOND_CHAT_ABANDONED_AFTER_JOIN,
                    relatedMatchId = chat.matchId,
                    relatedConnectionId = connection.id,
                    relatedChatId = chat.id,
                    occurredAt = now
                )
            }
        )
    }

    private fun finishSecondChat(
        connection: Connection,
        chat: Chat,
        status: ChatStatus,
        endedReason: ChatEndReason,
        now: OffsetDateTime,
        reliabilityRecorder: () -> Unit
    ) {
        if (chat.status != ChatStatus.ACTIVE) {
            return
        }
        reliabilityRecorder()
        chat.status = status
        chat.endedReason = endedReason
        chat.endedAt = now
        chat.readOnlyUntil = now.plusMinutes(readOnlyRetentionMinutes)
        chatRepository.save(chat)
        auditEventService.record(
            eventType = AuditEventType.CHAT_ENDED,
            aggregateType = AuditAggregateType.CHAT,
            aggregateId = chat.id,
            metadata = mapOf(
                "chatType" to chat.chatType.name,
                "status" to chat.status.name,
                "endedReason" to endedReason.name,
                "matchId" to chat.matchId,
                "connectionId" to chat.connectionId
            )
        )
        bumpBoth(connection, "second_chat_conversation_read_only")
    }

    private fun cancelRequest(request: SecondChatResolutionRequest, now: OffsetDateTime) {
        request.status = SecondChatResolutionRequestStatus.CANCELLED
        request.resolvedAt = now
        resolutionRequestRepository.save(request)
    }

    private fun mutualCompletionCooldownUntil(connectionId: UUID, requesterUserId: UUID): OffsetDateTime? =
        resolutionRequestRepository.findLatestResolvedByRequesterAndType(
            connectionId = connectionId,
            requesterUserId = requesterUserId,
            type = SecondChatResolutionRequestType.MUTUAL_COMPLETION,
            statuses = listOf(
                SecondChatResolutionRequestStatus.REJECTED,
                SecondChatResolutionRequestStatus.TIMED_OUT,
                SecondChatResolutionRequestStatus.CANCELLED
            ),
            pageable = PageRequest.of(0, 1)
        ).firstOrNull()?.resolvedAt?.plusSeconds(mutualCompletionRequesterCooldownSeconds)

    private fun findPendingRequestForUpdate(connectionId: UUID): SecondChatResolutionRequest? =
        resolutionRequestRepository.findByConnectionIdAndStatusForUpdate(
            connectionId = connectionId,
            status = SecondChatResolutionRequestStatus.PENDING
        )

    private fun latestMessage(chat: Chat): ChatMessage? =
        chatMessageRepository.findTopByChatSessionIdOrderBySentAtDescIdDesc(chat.id)

    private fun rejectedConversationResolved(chatId: UUID): SecondChatMessageResult.RejectedAfterResolution =
        SecondChatMessageResult.RejectedAfterResolution(
            code = DomainErrorCode.SECOND_CHAT_CONVERSATION_ALREADY_RESOLVED,
            message = "Second chat $chatId is already resolved"
        )

    private fun completionNotAvailable(connectionId: UUID): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SECOND_CHAT_COMPLETION_NOT_AVAILABLE,
            message = "Second-chat completion is not available for connection $connectionId"
        )

    private fun inactivityClaimNotAvailable(connectionId: UUID): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SECOND_CHAT_INACTIVITY_CLAIM_NOT_AVAILABLE,
            message = "Second-chat inactivity claim is not available for connection $connectionId"
        )

    private fun emptyStatus(): SecondChatConversationStatus =
        SecondChatConversationStatus(
            activeResolutionRequest = null,
            chatStatus = null,
            endedReason = null,
            endedAt = null,
            readOnlyUntil = null,
            mutualCompletionEligibleAt = null,
            canRequestMutualCompletion = false,
            mutualCompletionCooldownUntil = null,
            inactivityClaimableAt = null,
            inactivityClosesAt = null,
            canClaimPartnerInactivity = false,
            mustRespondToPartner = false,
            lastMessageAt = null,
            lastMessageSenderId = null
        )

    private fun bumpBoth(connection: Connection, reason: String) {
        homeStateInvalidationService.bumpBoth(
            userAId = connection.userAId,
            userBId = connection.userBId,
            reason = reason
        )
    }

    private fun Connection.partnerUserId(userId: UUID): UUID =
        when (userId) {
            userAId -> userBId
            userBId -> userAId
            else -> throw AccessDeniedException("User $userId does not belong to connection $id")
        }

    private data class SecondChatConversationContext(
        val connection: Connection,
        val chat: Chat
    )
}
