package com.reals.backend.service

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.VisualReview
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class VisualResourceAccessPolicy(
    private val matchService: MatchService,
    private val visualReviewRepository: VisualReviewRepository,
    private val connectionRepository: ConnectionRepository,
    private val userBlockService: UserBlockService
) {

    fun requireCanAccess(
        matchId: UUID,
        requestingUserId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): VisualResourceAccess {
        val match = matchService.findByIdOrThrow(matchId)
        requireMatchParticipant(match, requestingUserId)
        userBlockService.requirePairNotBlocked(match.userAId, match.userBId)

        val review = visualReviewRepository.findByMatchId(matchId)
            ?: throw visualContentNotAvailable()

        return when (match.state) {
            MatchState.VISUAL_PHASE -> {
                requireVisualReviewNotExpired(review, now)
                VisualResourceAccess(match = match, review = review, connection = null)
            }

            MatchState.VISUAL_APPROVED -> {
                val connection = connectionRepository.findByMatchId(matchId)
                    ?: throw visualContentNotAvailable()
                requireConnectionParticipant(connection, requestingUserId)
                if (connection.state !in ACTIVE_VISUAL_APPROVED_CONNECTION_STATES) {
                    throw visualContentNotAvailable()
                }
                VisualResourceAccess(match = match, review = review, connection = connection)
            }

            MatchState.CHAT_ACTIVE,
            MatchState.CHAT_REJECTED,
            MatchState.VISUAL_REJECTED,
            MatchState.EXPIRED -> throw visualContentNotAvailable()
        }
    }

    private fun requireMatchParticipant(match: Match, requestingUserId: UUID) {
        if (requestingUserId != match.userAId && requestingUserId != match.userBId) {
            throw AccessDeniedException("User does not belong to match")
        }
    }

    private fun requireConnectionParticipant(connection: Connection, requestingUserId: UUID) {
        if (requestingUserId != connection.userAId && requestingUserId != connection.userBId) {
            throw AccessDeniedException("User does not belong to connection")
        }
    }

    private fun requireVisualReviewNotExpired(review: VisualReview, now: OffsetDateTime) {
        val expiresAt = review.expiresAt
            ?: throw visualContentNotAvailable()

        if (!now.isBefore(expiresAt)) {
            throw DomainConflictException(
                code = DomainErrorCode.VISUAL_REVIEW_EXPIRED,
                message = "Visual content is no longer available"
            )
        }
    }

    private fun visualContentNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE,
            message = "Visual content is not available"
        )

    companion object {
        val ACTIVE_VISUAL_APPROVED_CONNECTION_STATES: Set<ConnectionState> =
            setOf(
                ConnectionState.SCHEDULING_PENDING,
                ConnectionState.SCHEDULING_PHASE,
                ConnectionState.SECOND_CHAT_SCHEDULED,
                ConnectionState.SECOND_CHAT_AVAILABLE,
                ConnectionState.SECOND_CHAT
            )
    }
}

data class VisualResourceAccess(
    val match: Match,
    val review: VisualReview,
    val connection: Connection?
)
