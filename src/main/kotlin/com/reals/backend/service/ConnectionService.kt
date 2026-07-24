package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ConnectionHomeDismissalRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.*

@Service
@Transactional
class ConnectionService(
    private val connectionRepository: ConnectionRepository,
    private val chatRepository: ChatRepository,
    private val dismissalRepository: ConnectionHomeDismissalRepository,
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val lockRepository: ActiveEngagementLockRepository,
    private val userService: UserService,
    private val userBlockService: UserBlockService,
    private val homeStateInvalidationService: HomeStateInvalidationService,

    @param:Value($$"${engagement.max-active-connections:2}")
    private val maxActiveConnections: Int,

    @param:Value($$"${scheduling.negotiation-duration-minutes:2880}")
    private val negotiationDurationMinutes: Long,

    @param:Value($$"${scheduling.activation-delay-minutes:5}")
    private val schedulingActivationDelayMinutes: Long,

    @param:Value($$"${chat.second-chat.duration-minutes:120}")
    private val secondChatDurationMinutes: Long

) {

    fun findByIdOrThrow(connectionId: UUID): Connection {
        return connectionRepository.findById(connectionId)
            .orElseThrow {
                NoSuchElementException("Connection not found: $connectionId")
            }
    }

    fun lockByIdOrThrow(connectionId: UUID): Connection =
        connectionRepository.findByIdForUpdate(connectionId)
            ?: throw NoSuchElementException("Connection not found: $connectionId")

    fun findByIdForUserOrThrow(
        connectionId: UUID,
        userId: UUID
    ): Connection {
        val connection = findByIdOrThrow(connectionId)
        validateParticipant(connection, userId)
        return connection
    }

    /**
     * Returns the Connection ID linked to a match, or null if not created yet.
     */
    fun findConnectionIdByMatchId(matchId: UUID): UUID? {
        return connectionRepository.findByMatchId(matchId)?.id
    }

    /**
     * Creates an internal pending Connection after MatchState = VISUAL_APPROVED.
     * The pending connection counts as active immediately by creating CONNECTION
     * locks, while the actionable scheduling phase is activated later.
     */
    fun createFromMatch(match: Match): Connection {
        userBlockService.requirePairNotBlocked(match.userAId, match.userBId)

        connectionRepository.findByMatchId(match.id)?.let { existing ->
            ensureConnectionLocks(existing)
            return existing
        }
        val now = OffsetDateTime.now()
        val schedulingAvailableAt = now.plusMinutes(schedulingActivationDelayMinutes)

        val connection = connectionRepository.save(
            Connection(
                matchId = match.id,
                userAId = match.userAId,
                userBId = match.userBId,
                state = ConnectionState.SCHEDULING_PENDING,
                schedulingAvailableAt = schedulingAvailableAt,
                schedulingExpiresAt = schedulingAvailableAt.plusMinutes(negotiationDurationMinutes)
            )
        )

        ensureConnectionLocks(connection)
        homeStateInvalidationService.bumpBoth(
            userAId = connection.userAId,
            userBId = connection.userBId,
            reason = "connection_created"
        )

        return connection
    }

    /**
     * Enables scheduling when the deferred availability time has arrived.
     * Idempotent so a job retry can safely continue to negotiation initialization.
     */
    fun activateScheduling(connectionId: UUID): Connection {

        val connection = findByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)

        if (connection.state == ConnectionState.SCHEDULING_PHASE) {
            ensureConnectionLocks(connection)
            return connection
        }

        check(connection.state == ConnectionState.SCHEDULING_PENDING) {
            "Cannot activate scheduling: connection is in state ${connection.state}"
        }

        val now = OffsetDateTime.now()
        val availableAt = checkNotNull(connection.schedulingAvailableAt) {
            "Cannot activate scheduling: schedulingAvailableAt is not set"
        }

        check(!availableAt.isAfter(now)) {
            "Cannot activate scheduling before $availableAt"
        }

        ensureConnectionLocks(connection)

        connection.state = ConnectionState.SCHEDULING_PHASE
        connection.schedulingExpiresAt = now.plusMinutes(negotiationDurationMinutes)
        connection.updatedAt = now

        val saved = connectionRepository.save(connection)
        homeStateInvalidationService.bumpBoth(
            userAId = saved.userAId,
            userBId = saved.userBId,
            reason = "scheduling_available"
        )
        return saved
    }

    private fun checkConnectionLimit(userId: UUID) {

        val active = lockRepository.countByUserIdAndEngagementType(
            userId,
            EngagementType.CONNECTION
        )

        check(active < maxActiveConnections) {
            "User $userId has reached the maximum number of active connections ($maxActiveConnections)"
        }
    }

    private fun validateParticipant(
        connection: Connection,
        userId: UUID
    ) {
        if (userId != connection.userAId && userId != connection.userBId) {
            throw AccessDeniedException("User $userId does not belong to connection ${connection.id}")
        }
    }

    private fun ensureConnectionLocks(connection: Connection) {
        userService.lockActiveUsersOrThrow(
            listOf(connection.userAId, connection.userBId),
            "Cannot activate scheduling: one or more users were not found"
        )

        ensureConnectionLock(
            userId = connection.userAId,
            connectionId = connection.id
        )
        ensureConnectionLock(
            userId = connection.userBId,
            connectionId = connection.id
        )
    }

    private fun ensureConnectionLock(
        userId: UUID,
        connectionId: UUID
    ) {
        if (
            lockRepository.existsByUserIdAndEngagementIdAndEngagementType(
                userId = userId,
                engagementId = connectionId,
                engagementType = EngagementType.CONNECTION
            )
        ) {
            return
        }

        checkConnectionLimit(userId)

        lockRepository.save(
            ActiveEngagementLock(
                userId = userId,
                engagementId = connectionId,
                engagementType = EngagementType.CONNECTION
            )
        )
    }

    /**
     * Transitions a Connection from SCHEDULING_PHASE to SECOND_CHAT_SCHEDULED.
     * Called when scheduling negotiation is confirmed for a future second chat slot.
     */
    fun transitionToSecondChatScheduled(connectionId: UUID): Connection {

        val connection = findByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)

        if (connection.state == ConnectionState.SECOND_CHAT_SCHEDULED) {
            return connection
        }

        check(connection.state == ConnectionState.SCHEDULING_PHASE) {
            "Cannot transition to SECOND_CHAT_SCHEDULED: connection is in state ${connection.state}"
        }

        connection.state = ConnectionState.SECOND_CHAT_SCHEDULED
        connection.updatedAt = OffsetDateTime.now()

        val saved = connectionRepository.save(connection)
        homeStateInvalidationService.bumpBoth(
            userAId = saved.userAId,
            userBId = saved.userBId,
            reason = "second_chat_scheduled"
        )
        return saved
    }

    /**
     * Transitions a scheduled Connection to SECOND_CHAT_AVAILABLE when the confirmed
     * second-chat time has arrived and the chat session is visible to participants.
     */
    fun transitionToSecondChatAvailable(connectionId: UUID): Connection {

        val connection = findByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)

        if (connection.state == ConnectionState.SECOND_CHAT_AVAILABLE) {
            return connection
        }

        check(connection.state == ConnectionState.SECOND_CHAT_SCHEDULED) {
            "Cannot transition to SECOND_CHAT_AVAILABLE: connection is in state ${connection.state}"
        }

        connection.state = ConnectionState.SECOND_CHAT_AVAILABLE
        connection.updatedAt = OffsetDateTime.now()

        val saved = connectionRepository.save(connection)
        homeStateInvalidationService.bumpBoth(
            userAId = saved.userAId,
            userBId = saved.userBId,
            reason = "second_chat_available"
        )
        return saved
    }

    /**
     * Transitions an available second-chat Connection to SECOND_CHAT when a participant
     * enters the chat or sends the first message.
     */
    fun transitionToSecondChat(connectionId: UUID): Connection {

        val connection = findByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)

        if (connection.state == ConnectionState.SECOND_CHAT) {
            return connection
        }

        check(connection.state == ConnectionState.SECOND_CHAT_AVAILABLE) {
            "Cannot transition to SECOND_CHAT: connection is in state ${connection.state}"
        }

        connection.state = ConnectionState.SECOND_CHAT
        connection.updatedAt = OffsetDateTime.now()

        val saved = connectionRepository.save(connection)
        homeStateInvalidationService.bumpBoth(
            userAId = saved.userAId,
            userBId = saved.userBId,
            reason = "second_chat_entered"
        )
        return saved
    }

    fun transitionToSecondChatIdempotent(connectionId: UUID): Connection {
        val connection = findByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)

        check(
            connection.state == ConnectionState.SECOND_CHAT_SCHEDULED ||
                connection.state == ConnectionState.SECOND_CHAT_AVAILABLE ||
                connection.state == ConnectionState.SECOND_CHAT
        ) {
            "Cannot transition to SECOND_CHAT: connection is in state ${connection.state}"
        }

        if (connection.state == ConnectionState.SECOND_CHAT) {
            return connection
        }

        val previousState = connection.state
        val transitioned = connectionRepository.transitionToSecondChatIfAllowed(
            connectionId = connectionId,
            updatedAt = OffsetDateTime.now()
        )
        val updated = findByIdOrThrow(connectionId)

        check(updated.state == ConnectionState.SECOND_CHAT) {
            "Cannot transition to SECOND_CHAT: connection is in state ${updated.state}"
        }

        if (transitioned == 1) {
            if (previousState == ConnectionState.SECOND_CHAT_SCHEDULED) {
                homeStateInvalidationService.bumpBoth(
                    userAId = updated.userAId,
                    userBId = updated.userBId,
                    reason = "second_chat_available"
                )
            }
            homeStateInvalidationService.bumpBoth(
                userAId = updated.userAId,
                userBId = updated.userBId,
                reason = "second_chat_entered"
            )
        }

        return updated
    }

    /**
     * Closes a Connection and releases both user locks.
     */
    fun closeConnection(connectionId: UUID): Boolean {

        val connection = findByIdOrThrow(connectionId)

        if (connection.state == ConnectionState.CLOSED) {
            return false
        }

        check(
            connection.state == ConnectionState.SCHEDULING_PENDING ||
            connection.state == ConnectionState.SCHEDULING_PHASE ||
            connection.state == ConnectionState.SECOND_CHAT_SCHEDULED ||
            connection.state == ConnectionState.SECOND_CHAT_AVAILABLE ||
            connection.state == ConnectionState.SECOND_CHAT
        ) {
            "Cannot close connection: connection is in state ${connection.state}"
        }

        connection.state = ConnectionState.CLOSED
        connection.updatedAt = OffsetDateTime.now()

        connectionRepository.save(connection)

        lockRepository.deleteByEngagementId(connection.id)
        homeStateInvalidationService.bumpBoth(
            userAId = connection.userAId,
            userBId = connection.userBId,
            reason = "connection_closed"
        )

        return true
    }

    fun dismissSecondChatFromHome(
        connectionId: UUID,
        userId: UUID
    ): ConnectionHomeDismissal {
        val connection =
            findByIdForUserOrThrow(
                connectionId = connectionId,
                userId = userId
            )

        val existing =
            dismissalRepository.findByUserIdAndConnectionId(
                userId = userId,
                connectionId = connectionId
            )

        if (existing != null) {
            return existing
        }

        check(isSecondChatDismissible(connection)) {
            "Second chat for connection $connectionId is still actionable"
        }

        val dismissal = dismissalRepository.save(
            ConnectionHomeDismissal(
                userId = userId,
                connectionId = connectionId
            )
        )
        homeStateInvalidationService.bump(
            userId = userId,
            reason = "connection_home_dismissed"
        )
        return dismissal
    }

    private fun isSecondChatDismissible(connection: Connection): Boolean {
        if (connection.state == ConnectionState.CLOSED) {
            return true
        }

        val now = OffsetDateTime.now()
        val secondChat =
            chatRepository.findByConnectionIdAndChatType(
                connectionId = connection.id,
                chatType = ChatType.SECOND_CHAT
            )

        return when (connection.state) {
            ConnectionState.SECOND_CHAT_SCHEDULED ->
                secondChat == null &&
                    negotiationRepository.findByConnectionId(connection.id)
                        ?.confirmedDateTime
                        ?.plusMinutes(secondChatDurationMinutes)
                        ?.let { !it.isAfter(now) } == true

            ConnectionState.SECOND_CHAT_AVAILABLE ->
                secondChat != null &&
                    secondChat.status == ChatStatus.AVAILABLE &&
                    !secondChat.timeoutAt.isAfter(now)

            ConnectionState.SECOND_CHAT ->
                when (secondChat?.status) {
                    ChatStatus.EXPIRED,
                    ChatStatus.ABANDONED,
                    ChatStatus.CLOSED -> true
                    ChatStatus.AVAILABLE,
                    ChatStatus.ACTIVE -> !secondChat.timeoutAt.isAfter(now)
                    else -> false
                }

            ConnectionState.SCHEDULING_PENDING,
            ConnectionState.SCHEDULING_PHASE,
            ConnectionState.CLOSED -> false
        }
    }
}
