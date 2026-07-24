package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.domain.SecondChatParticipation
import com.reals.backend.domain.SecondChatResolutionRequest
import com.reals.backend.domain.SecondChatResolutionRequestStatus
import com.reals.backend.domain.SecondChatResolutionRequestType
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.SecondChatParticipationRepository
import com.reals.backend.repository.SecondChatResolutionRequestRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
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
class SecondChatLifecycleService(
    private val connectionRepository: ConnectionRepository,
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val chatRepository: ChatRepository,
    private val participationRepository: SecondChatParticipationRepository,
    private val resolutionRequestRepository: SecondChatResolutionRequestRepository,
    private val chatService: ChatService,
    private val connectionService: ConnectionService,
    private val userBlockService: UserBlockService,
    private val userReliabilityScoreService: UserReliabilityScoreService,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,

    @param:Value("\${chat.second-chat.duration-minutes:120}")
    private val secondChatDurationMinutes: Long,

    @param:Value("\${chat.second-chat.read-only-retention-minutes:1440}")
    private val secondChatReadOnlyRetentionMinutes: Long,

    @param:Value("\${chat.second-chat.on-time-window-minutes:10}")
    private val onTimeWindowMinutes: Long,

    @param:Value("\${chat.second-chat.entry-window-minutes:20}")
    private val entryWindowMinutes: Long,

    @param:Value("\${chat.second-chat.no-show-claim-countdown-seconds:60}")
    private val noShowClaimCountdownSeconds: Long
) {

    data class SecondChatAttendanceView(
        val connectionId: UUID,
        val chatId: UUID?,
        val scheduledAt: OffsetDateTime,
        val onTimeUntil: OffsetDateTime,
        val entryClosesAt: OffsetDateTime,
        val absoluteExpiresAt: OffsetDateTime,
        val conversationStartedAt: OffsetDateTime?,
        val serverTime: OffsetDateTime,
        val myAttendanceStatus: SecondChatAttendanceStatus,
        val myJoinedAt: OffsetDateTime?,
        val partnerAttendanceStatus: SecondChatAttendanceStatus,
        val partnerJoinedAt: OffsetDateTime?,
        val canJoin: Boolean,
        val canClaimPartnerNoShow: Boolean,
        val activeNoShowClaim: SecondChatResolutionRequest?
    )

    fun joinSecondChat(
        connectionId: UUID,
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): SecondChatAttendanceView {
        val context = lockedConfirmedContext(connectionId, userId)
        val scheduledAt = context.confirmedDateTime
        val entryClosesAt = scheduledAt.plusMinutes(entryWindowMinutes)

        if (now.isBefore(scheduledAt)) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_NOT_AVAILABLE_YET,
                message = "Second chat for connection $connectionId is available at $scheduledAt"
            )
        }

        val participations = ensureParticipationsForUpdate(context.connection)
        val myParticipation = participations.byUser(userId)

        if (!now.isBefore(entryClosesAt)) {
            resolveHardCutoffNoShowLocked(context = context, now = now)
            if (myParticipation.hasJoined()) {
                return buildStatusView(context.connection, context.confirmedDateTime, userId, now)
            }
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_ENTRY_CLOSED,
                message = "Second-chat entry closed for connection $connectionId at $entryClosesAt"
            )
        }

        if (myParticipation.attendanceStatus == SecondChatAttendanceStatus.NO_SHOW) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_ALREADY_RESOLVED,
                message = "Second chat for connection $connectionId is already resolved for this participant"
            )
        }

        val existingChat = chatRepository.findByConnectionIdAndChatTypeForUpdate(connectionId, ChatType.SECOND_CHAT)
        if (existingChat.isTerminal()) {
            if (myParticipation.hasJoined()) {
                return buildStatusView(context.connection, context.confirmedDateTime, userId, now)
            }
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_ALREADY_RESOLVED,
                message = "Second chat for connection $connectionId is already resolved"
            )
        }

        val chat = materializeOrActivateSecondChat(
            connection = context.connection,
            existingChat = existingChat,
            scheduledAt = scheduledAt,
            now = now
        )

        if (myParticipation.attendanceStatus == SecondChatAttendanceStatus.PENDING) {
            val status =
                if (now.isBefore(scheduledAt.plusMinutes(onTimeWindowMinutes))) {
                    SecondChatAttendanceStatus.ON_TIME
                } else {
                    SecondChatAttendanceStatus.LATE
                }
            myParticipation.attendanceStatus = status
            myParticipation.joinedAt = now
            myParticipation.resolvedAt = now
            myParticipation.updatedAt = now
            participationRepository.save(myParticipation)
            recordAttendanceEvent(context.connection, chat, userId, status, now)
        }

        val refreshedParticipations = participationRepository.findByConnectionIdForUpdate(connectionId)
        if (refreshedParticipations.countJoined() == 2) {
            cancelPendingClaim(connectionId, now)
            setConversationStartedAtIfNeeded(chat, refreshedParticipations)
        }

        homeStateInvalidationService.bumpBoth(
            userAId = context.connection.userAId,
            userBId = context.connection.userBId,
            reason = "second_chat_joined"
        )

        return buildStatusView(context.connection, context.confirmedDateTime, userId, now)
    }

    fun getSecondChatStatus(
        connectionId: UUID,
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): SecondChatAttendanceView {
        val connection = connectionService.findByIdForUserOrThrow(connectionId, userId)
        val negotiation = negotiationRepository.findByConnectionId(connectionId)
            ?: throw secondChatNotAvailable("Second chat is not scheduled for connection $connectionId")
        val confirmedDateTime = negotiation.confirmedDateTime
        if (negotiation.status != NegotiationStatus.CONFIRMED || confirmedDateTime == null) {
            throw secondChatNotAvailable("Second chat is not confirmed for connection $connectionId")
        }

        return buildStatusView(connection, confirmedDateTime, userId, now)
    }

    fun createPartnerNoShowClaim(
        connectionId: UUID,
        requesterUserId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): SecondChatAttendanceView {
        val context = lockedConfirmedContext(connectionId, requesterUserId)
        val scheduledAt = context.confirmedDateTime
        val onTimeUntil = scheduledAt.plusMinutes(onTimeWindowMinutes)
        val entryClosesAt = scheduledAt.plusMinutes(entryWindowMinutes)

        if (now.isBefore(onTimeUntil) || !now.isBefore(entryClosesAt)) {
            throw noShowClaimNotAvailable(connectionId)
        }

        val participations = ensureParticipationsForUpdate(context.connection)
        val requester = participations.byUser(requesterUserId)
        val responderUserId = context.connection.partnerUserId(requesterUserId)
        val responder = participations.byUser(responderUserId)

        if (!requester.hasJoined() || responder.hasJoined() || responder.attendanceStatus == SecondChatAttendanceStatus.NO_SHOW) {
            throw noShowClaimNotAvailable(connectionId)
        }

        val chat = chatRepository.findByConnectionIdAndChatTypeForUpdate(connectionId, ChatType.SECOND_CHAT)
        if (chat.isTerminal()) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_ALREADY_RESOLVED,
                message = "Second chat for connection $connectionId is already resolved"
            )
        }

        val pending =
            resolutionRequestRepository.findByConnectionIdAndTypeAndStatus(
                connectionId = connectionId,
                type = SecondChatResolutionRequestType.PARTNER_NO_SHOW,
                status = SecondChatResolutionRequestStatus.PENDING
            )

        if (pending != null) {
            if (pending.requesterUserId == requesterUserId && pending.responderUserId == responderUserId) {
                return buildStatusView(context.connection, scheduledAt, requesterUserId, now)
            }
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_NO_SHOW_CLAIM_ALREADY_PENDING,
                message = "A pending no-show claim already exists for connection $connectionId"
            )
        }

        val materializedChat = materializeOrActivateSecondChat(
            connection = context.connection,
            existingChat = chat,
            scheduledAt = scheduledAt,
            now = now
        )
        val expiresAt = minOf(now.plusSeconds(noShowClaimCountdownSeconds), entryClosesAt)
        resolutionRequestRepository.saveAndFlush(
            SecondChatResolutionRequest(
                connectionId = connectionId,
                chatId = materializedChat.id,
                requesterUserId = requesterUserId,
                responderUserId = responderUserId,
                type = SecondChatResolutionRequestType.PARTNER_NO_SHOW,
                status = SecondChatResolutionRequestStatus.PENDING,
                createdAt = now,
                expiresAt = expiresAt
            )
        )

        homeStateInvalidationService.bumpBoth(
            userAId = context.connection.userAId,
            userBId = context.connection.userBId,
            reason = "second_chat_no_show_claim_created"
        )

        return buildStatusView(context.connection, scheduledAt, requesterUserId, now)
    }

    fun initializeParticipationsForConfirmedConnection(connectionId: UUID) {
        val connection = connectionService.findByIdOrThrow(connectionId)
        ensureParticipations(connection)
    }

    fun findExpiredPendingNoShowClaimIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> {
        require(limit > 0) { "Expired no-show claim candidate limit must be positive" }
        return resolutionRequestRepository.findExpiredPendingPartnerNoShowRequestIds(
            now = now,
            pageable = PageRequest.of(0, limit)
        )
    }

    fun processExpiredNoShowClaim(
        requestId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean {
        val snapshot = resolutionRequestRepository.findById(requestId).orElse(null) ?: return false
        if (
            snapshot.type != SecondChatResolutionRequestType.PARTNER_NO_SHOW ||
            snapshot.status != SecondChatResolutionRequestStatus.PENDING ||
            snapshot.expiresAt.isAfter(now)
        ) {
            return false
        }

        val connection = connectionRepository.findByIdForUpdate(snapshot.connectionId) ?: return false
        val request = resolutionRequestRepository.findByIdForUpdate(requestId) ?: return false
        if (
            request.type != SecondChatResolutionRequestType.PARTNER_NO_SHOW ||
            request.status != SecondChatResolutionRequestStatus.PENDING ||
            request.expiresAt.isAfter(now)
        ) {
            return false
        }

        val negotiation = negotiationRepository.findByConnectionIdForUpdate(connection.id) ?: return false
        val confirmedDateTime = negotiation.confirmedDateTime ?: return false
        if (negotiation.status != NegotiationStatus.CONFIRMED) {
            return false
        }

        val participations = ensureParticipationsForUpdate(connection)
        val responder = participations.byUser(request.responderUserId)
        if (responder.hasJoined()) {
            request.status = SecondChatResolutionRequestStatus.CANCELLED
            request.resolvedAt = now
            resolutionRequestRepository.save(request)
            return true
        }

        if (responder.attendanceStatus != SecondChatAttendanceStatus.NO_SHOW) {
            markNoShow(
                connection = connection,
                chat = chatRepository.findByConnectionIdAndChatTypeForUpdate(connection.id, ChatType.SECOND_CHAT),
                participation = responder,
                now = now
            )
        }
        request.status = SecondChatResolutionRequestStatus.COMPLETED
        request.resolvedAt = now
        resolutionRequestRepository.save(request)
        terminateForNoShow(connection, now)

        return true
    }

    fun findHardCutoffNoShowConnectionIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> {
        require(limit > 0) { "Hard-cutoff no-show candidate limit must be positive" }
        return negotiationRepository.findConfirmedSecondChatHardCutoffDueConnectionIds(
            dueBefore = now.minusMinutes(entryWindowMinutes),
            pageable = PageRequest.of(0, limit)
        )
    }

    fun resolveHardCutoffNoShow(
        connectionId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean {
        val context =
            try {
                lockedConfirmedContext(connectionId, null)
            } catch (ex: DomainConflictException) {
                if (ex.code == DomainErrorCode.SECOND_CHAT_NOT_AVAILABLE) {
                    return false
                }
                throw ex
            }
        val entryClosesAt = context.confirmedDateTime.plusMinutes(entryWindowMinutes)
        if (now.isBefore(entryClosesAt)) {
            return false
        }
        return resolveHardCutoffNoShowLocked(context, now)
    }

    private fun resolveHardCutoffNoShowLocked(
        context: ConfirmedSecondChatContext,
        now: OffsetDateTime
    ): Boolean {
        val participations = ensureParticipationsForUpdate(context.connection)
        if (participations.none { it.attendanceStatus == SecondChatAttendanceStatus.PENDING }) {
            return false
        }

        val chat = chatRepository.findByConnectionIdAndChatTypeForUpdate(context.connection.id, ChatType.SECOND_CHAT)
        val joinedCount = participations.countJoined()

        if (joinedCount == 2) {
            cancelPendingClaim(context.connection.id, now)
            chat?.let { setConversationStartedAtIfNeeded(it, participations) }
            return false
        }

        participations
            .filter { it.attendanceStatus == SecondChatAttendanceStatus.PENDING }
            .forEach { markNoShow(context.connection, chat, it, now) }

        completePendingClaim(context.connection.id, now)
        if (joinedCount == 0 && chat == null) {
            connectionService.closeConnection(context.connection.id)
        } else {
            terminateForNoShow(context.connection, now)
        }

        return true
    }

    private fun lockedConfirmedContext(
        connectionId: UUID,
        userId: UUID?
    ): ConfirmedSecondChatContext {
        val connection = connectionRepository.findByIdForUpdate(connectionId)
            ?: throw NoSuchElementException("Connection not found: $connectionId")
        if (userId != null && userId != connection.userAId && userId != connection.userBId) {
            throw AccessDeniedException("User $userId does not belong to connection $connectionId")
        }
        if (connection.state !in secondChatLifecycleStates) {
            throw secondChatNotAvailable(
                "Second chat is not available while connection $connectionId is in state ${connection.state}"
            )
        }
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)

        val negotiation = negotiationRepository.findByConnectionIdForUpdate(connectionId)
            ?: throw secondChatNotAvailable("Second chat is not scheduled for connection $connectionId")
        val confirmedDateTime = negotiation.confirmedDateTime
        if (negotiation.status != NegotiationStatus.CONFIRMED || confirmedDateTime == null) {
            throw secondChatNotAvailable("Second chat is not confirmed for connection $connectionId")
        }

        return ConfirmedSecondChatContext(
            connection = connection,
            confirmedDateTime = confirmedDateTime
        )
    }

    private fun ensureParticipationsForUpdate(connection: Connection): List<SecondChatParticipation> {
        ensureParticipations(connection)
        return participationRepository.findByConnectionIdForUpdate(connection.id)
    }

    private fun ensureParticipations(connection: Connection): List<SecondChatParticipation> {
        val existing = participationRepository.findByConnectionId(connection.id)
        val existingUserIds = existing.map { it.userId }.toSet()
        val missing = listOf(connection.userAId, connection.userBId)
            .filter { it !in existingUserIds }
            .map {
                SecondChatParticipation(
                    connectionId = connection.id,
                    userId = it
                )
            }
        return if (missing.isEmpty()) {
            existing
        } else {
            existing + participationRepository.saveAllAndFlush(missing)
        }
    }

    private fun materializeOrActivateSecondChat(
        connection: Connection,
        existingChat: Chat?,
        scheduledAt: OffsetDateTime,
        now: OffsetDateTime
    ): Chat {
        if (existingChat == null) {
            val chat = chatService.startSecondChat(
                matchId = connection.matchId,
                connectionId = connection.id,
                availableAt = scheduledAt,
                activatedAt = now
            )
            connectionService.transitionToSecondChatIdempotent(connection.id)
            return chat
        }

        if (existingChat.status == ChatStatus.AVAILABLE) {
            chatRepository.activateAvailableSecondChat(existingChat.id, now)
            connectionService.transitionToSecondChatIdempotent(connection.id)
            return chatRepository.findByIdForUpdate(existingChat.id)
                ?: throw NoSuchElementException("Chat not found: ${existingChat.id}")
        }

        if (existingChat.status == ChatStatus.ACTIVE) {
            connectionService.transitionToSecondChatIdempotent(connection.id)
            return existingChat
        }

        throw DomainConflictException(
            code = DomainErrorCode.SECOND_CHAT_ALREADY_RESOLVED,
            message = "Second chat for connection ${connection.id} is already resolved"
        )
    }

    private fun setConversationStartedAtIfNeeded(
        chat: Chat,
        participations: List<SecondChatParticipation>
    ) {
        if (chat.conversationStartedAt != null || chat.status != ChatStatus.ACTIVE) {
            return
        }
        val joinedAt = participations.mapNotNull { it.joinedAt }
        if (joinedAt.size != 2) {
            return
        }
        chat.conversationStartedAt = joinedAt.maxOrNull()
        chatRepository.save(chat)
    }

    private fun recordAttendanceEvent(
        connection: Connection,
        chat: Chat,
        userId: UUID,
        status: SecondChatAttendanceStatus,
        now: OffsetDateTime
    ) {
        val eventType = when (status) {
            SecondChatAttendanceStatus.ON_TIME -> UserReliabilityEventType.SECOND_CHAT_CONFIRMED_ATTENDED
            SecondChatAttendanceStatus.LATE -> UserReliabilityEventType.SECOND_CHAT_LATE_ARRIVAL
            SecondChatAttendanceStatus.PENDING,
            SecondChatAttendanceStatus.NO_SHOW -> return
        }

        userReliabilityScoreService.recordEvent(
            userId = userId,
            eventType = eventType,
            relatedMatchId = connection.matchId,
            relatedConnectionId = connection.id,
            relatedChatId = chat.id,
            occurredAt = now
        )
    }

    private fun markNoShow(
        connection: Connection,
        chat: Chat?,
        participation: SecondChatParticipation,
        now: OffsetDateTime
    ) {
        if (participation.attendanceStatus == SecondChatAttendanceStatus.NO_SHOW) {
            return
        }
        if (participation.hasJoined()) {
            return
        }

        participation.attendanceStatus = SecondChatAttendanceStatus.NO_SHOW
        participation.resolvedAt = now
        participation.updatedAt = now
        participationRepository.save(participation)

        userReliabilityScoreService.recordEvent(
            userId = participation.userId,
            eventType = UserReliabilityEventType.SECOND_CHAT_NO_SHOW,
            relatedMatchId = connection.matchId,
            relatedConnectionId = connection.id,
            relatedChatId = chat?.id,
            occurredAt = now
        )
    }

    private fun terminateForNoShow(
        connection: Connection,
        now: OffsetDateTime
    ) {
        val chat = chatRepository.findByConnectionIdAndChatTypeForUpdate(connection.id, ChatType.SECOND_CHAT)
        if (chat == null) {
            connectionService.closeConnection(connection.id)
            return
        }
        if (chat.status == ChatStatus.CLOSED) {
            return
        }
        if (chat.status != ChatStatus.ABANDONED) {
            chat.status = ChatStatus.ABANDONED
            chat.endedReason = ChatEndReason.SECOND_CHAT_NO_SHOW
            chat.endedAt = now
            chat.readOnlyUntil = now.plusMinutes(secondChatReadOnlyRetentionMinutes)
            chatRepository.save(chat)
            recordChatEnded(chat)
        }
        homeStateInvalidationService.bumpBoth(
            userAId = connection.userAId,
            userBId = connection.userBId,
            reason = "second_chat_no_show_read_only"
        )
    }

    private fun cancelPendingClaim(
        connectionId: UUID,
        now: OffsetDateTime
    ) {
        val pending =
            resolutionRequestRepository.findByConnectionIdAndTypeAndStatus(
                connectionId = connectionId,
                type = SecondChatResolutionRequestType.PARTNER_NO_SHOW,
                status = SecondChatResolutionRequestStatus.PENDING
            ) ?: return
        pending.status = SecondChatResolutionRequestStatus.CANCELLED
        pending.resolvedAt = now
        resolutionRequestRepository.save(pending)
    }

    private fun completePendingClaim(
        connectionId: UUID,
        now: OffsetDateTime
    ) {
        resolutionRequestRepository.findByConnectionIdAndStatus(
            connectionId = connectionId,
            status = SecondChatResolutionRequestStatus.PENDING
        ).forEach { request ->
            request.status = SecondChatResolutionRequestStatus.COMPLETED
            request.resolvedAt = now
            resolutionRequestRepository.save(request)
        }
    }

    private fun buildStatusView(
        connection: Connection,
        scheduledAt: OffsetDateTime,
        userId: UUID,
        now: OffsetDateTime
    ): SecondChatAttendanceView {
        val chat = chatRepository.findByConnectionIdAndChatType(connection.id, ChatType.SECOND_CHAT)
        val participations = participationRepository.findByConnectionId(connection.id)
        val myParticipation = participations.find { it.userId == userId }
        val partnerUserId = connection.partnerUserId(userId)
        val partnerParticipation = participations.find { it.userId == partnerUserId }
        val activeClaim =
            resolutionRequestRepository.findByConnectionIdAndTypeAndStatus(
                connectionId = connection.id,
                type = SecondChatResolutionRequestType.PARTNER_NO_SHOW,
                status = SecondChatResolutionRequestStatus.PENDING
            )
        val onTimeUntil = scheduledAt.plusMinutes(onTimeWindowMinutes)
        val entryClosesAt = scheduledAt.plusMinutes(entryWindowMinutes)
        val terminal = connection.state == ConnectionState.CLOSED || chat.isTerminal()
        val myStatus = myParticipation?.attendanceStatus ?: SecondChatAttendanceStatus.PENDING
        val partnerStatus = partnerParticipation?.attendanceStatus ?: SecondChatAttendanceStatus.PENDING

        return SecondChatAttendanceView(
            connectionId = connection.id,
            chatId = chat?.id,
            scheduledAt = scheduledAt,
            onTimeUntil = onTimeUntil,
            entryClosesAt = entryClosesAt,
            absoluteExpiresAt = chat?.timeoutAt ?: scheduledAt.plusMinutes(secondChatDurationMinutes),
            conversationStartedAt = chat?.conversationStartedAt,
            serverTime = now,
            myAttendanceStatus = myStatus,
            myJoinedAt = myParticipation?.joinedAt,
            partnerAttendanceStatus = partnerStatus,
            partnerJoinedAt = partnerParticipation?.joinedAt,
            canJoin = !terminal &&
                myStatus == SecondChatAttendanceStatus.PENDING &&
                !now.isBefore(scheduledAt) &&
                now.isBefore(entryClosesAt),
            canClaimPartnerNoShow = !terminal &&
                myParticipation?.hasJoined() == true &&
                partnerParticipation?.hasJoined() != true &&
                partnerStatus != SecondChatAttendanceStatus.NO_SHOW &&
                !now.isBefore(onTimeUntil) &&
                now.isBefore(entryClosesAt) &&
                activeClaim == null,
            activeNoShowClaim = activeClaim
        )
    }

    private fun recordChatEnded(chat: Chat) {
        auditEventService.record(
            eventType = AuditEventType.CHAT_ENDED,
            aggregateType = AuditAggregateType.CHAT,
            aggregateId = chat.id,
            metadata = mapOf(
                "chatType" to chat.chatType.name,
                "status" to chat.status.name,
                "endedReason" to chat.endedReason?.name,
                "matchId" to chat.matchId,
                "connectionId" to chat.connectionId
            )
        )
    }

    private fun noShowClaimNotAvailable(connectionId: UUID): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SECOND_CHAT_NO_SHOW_CLAIM_NOT_AVAILABLE,
            message = "Partner no-show claim is not available for connection $connectionId"
        )

    private fun secondChatNotAvailable(message: String): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SECOND_CHAT_NOT_AVAILABLE,
            message = message
        )

    private fun List<SecondChatParticipation>.byUser(userId: UUID): SecondChatParticipation =
        firstOrNull { it.userId == userId }
            ?: error("Second-chat participation missing for user $userId")

    private fun List<SecondChatParticipation>.countJoined(): Int =
        count { it.hasJoined() }

    private fun SecondChatParticipation.hasJoined(): Boolean =
        attendanceStatus == SecondChatAttendanceStatus.ON_TIME ||
            attendanceStatus == SecondChatAttendanceStatus.LATE

    private fun Chat?.isTerminal(): Boolean =
        this != null &&
            status in setOf(
                ChatStatus.CANCELLED,
                ChatStatus.EXPIRED,
                ChatStatus.ABANDONED,
                ChatStatus.CLOSED,
                ChatStatus.FINISHED
            )

    private fun Connection.partnerUserId(userId: UUID): UUID =
        when (userId) {
            userAId -> userBId
            userBId -> userAId
            else -> throw AccessDeniedException("User $userId does not belong to connection $id")
        }

    private data class ConfirmedSecondChatContext(
        val connection: Connection,
        val confirmedDateTime: OffsetDateTime
    )

    private companion object {
        val secondChatLifecycleStates = setOf(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE,
            ConnectionState.SECOND_CHAT
        )
    }
}
