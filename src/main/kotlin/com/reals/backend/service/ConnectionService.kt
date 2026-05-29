package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.UserRepository
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
    private val lockRepository: ActiveEngagementLockRepository,
    private val userRepository: UserRepository,

    @param:Value("\${engagement.max-active-connections:2}")
    private val maxActiveConnections: Int,

    @param:Value("\${scheduling.negotiation-duration-minutes:2880}")
    private val negotiationDurationMinutes: Long

) {

    fun findByIdOrThrow(connectionId: UUID): Connection {
        return connectionRepository.findById(connectionId)
            .orElseThrow {
                NoSuchElementException("Connection not found: $connectionId")
            }
    }

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
     * Creates a Connection after MatchState = VISUAL_APPROVED.
     * Validates that neither user exceeds maxActiveConnections before creating
     * Upgrades ActiveEngagementLock type from MATCH to CONNECTION.
     */
    fun createFromMatch(match: Match): Connection {

        connectionRepository.findByMatchId(match.id)?.let { return it }

        lockUsers(match.userAId, match.userBId)

        checkConnectionLimit(match.userAId)
        checkConnectionLimit(match.userBId)

        val connection = connectionRepository.save(
            Connection(
                matchId = match.id,
                userAId = match.userAId,
                userBId = match.userBId,
                schedulingExpiresAt = OffsetDateTime.now()
                    .plusMinutes(negotiationDurationMinutes)
            )
        )

        upgradeLock(
            userId = match.userAId,
            oldEngagementId = match.id,
            newEngagementId = connection.id
        )

        upgradeLock(
            userId = match.userBId,
            oldEngagementId = match.id,
            newEngagementId = connection.id
        )

        return connection
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

    private fun lockUsers(vararg userIds: UUID) {
        val distinctIds = userIds.distinct()
        val locked = userRepository.findAllByIdForUpdate(distinctIds)

        check(locked.size == distinctIds.size) {
            "Cannot create connection: one or more users were not found"
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

    private fun upgradeLock(
        userId: UUID,
        oldEngagementId: UUID,
        newEngagementId: UUID
    ) {

        lockRepository.deleteByEngagementId(oldEngagementId)

        lockRepository.save(
            ActiveEngagementLock(
                userId = userId,
                engagementId = newEngagementId,
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

        if (connection.state == ConnectionState.SECOND_CHAT_SCHEDULED) {
            return connection
        }

        check(connection.state == ConnectionState.SCHEDULING_PHASE) {
            "Cannot transition to SECOND_CHAT_SCHEDULED: connection is in state ${connection.state}"
        }

        connection.state = ConnectionState.SECOND_CHAT_SCHEDULED
        connection.updatedAt = OffsetDateTime.now()

        return connectionRepository.save(connection)
    }

    /**
     * Transitions a scheduled Connection to SECOND_CHAT_AVAILABLE when the confirmed
     * second-chat time has arrived and the chat session is visible to participants.
     */
    fun transitionToSecondChatAvailable(connectionId: UUID): Connection {

        val connection = findByIdOrThrow(connectionId)

        if (connection.state == ConnectionState.SECOND_CHAT_AVAILABLE) {
            return connection
        }

        check(connection.state == ConnectionState.SECOND_CHAT_SCHEDULED) {
            "Cannot transition to SECOND_CHAT_AVAILABLE: connection is in state ${connection.state}"
        }

        connection.state = ConnectionState.SECOND_CHAT_AVAILABLE
        connection.updatedAt = OffsetDateTime.now()

        return connectionRepository.save(connection)
    }

    /**
     * Transitions an available second-chat Connection to SECOND_CHAT when a participant
     * enters the chat or sends the first message.
     */
    fun transitionToSecondChat(connectionId: UUID): Connection {

        val connection = findByIdOrThrow(connectionId)

        if (connection.state == ConnectionState.SECOND_CHAT) {
            return connection
        }

        check(connection.state == ConnectionState.SECOND_CHAT_AVAILABLE) {
            "Cannot transition to SECOND_CHAT: connection is in state ${connection.state}"
        }

        connection.state = ConnectionState.SECOND_CHAT
        connection.updatedAt = OffsetDateTime.now()

        return connectionRepository.save(connection)
    }

    /**
     * Closes a Connection and releases both user locks.
     */
    fun closeConnection(connectionId: UUID) {

        val connection = findByIdOrThrow(connectionId)

        if (connection.state == ConnectionState.CLOSED) {
            return
        }

        check(
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
    }
}
