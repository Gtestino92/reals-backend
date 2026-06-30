package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import jakarta.transaction.Transactional
import org.springframework.security.access.AccessDeniedException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.*

@Service
@Transactional
class MatchService(

    private val matchRepository: MatchRepository,
    private val lockRepository: ActiveEngagementLockRepository,
    private val queueRepository: MatchmakingQueueRepository,
    private val userService: UserService,
    private val userBlockService: UserBlockService,

    @param:Value("\${engagement.max-active-matches:5}")
    private val maxActiveMatches: Int

) {

    fun findByIdOrThrow(matchId: UUID): Match {
        return matchRepository.findById(matchId)
            .orElseThrow {
                NoSuchElementException("Match not found: $matchId")
            }
    }

    fun findByIdForUserOrThrow(
        matchId: UUID,
        userId: UUID
    ): Match {
        val match = findByIdOrThrow(matchId)
        validateParticipant(match, userId)
        return match
    }

    /**
     * Creates a Match between two users, locks both and removes them from the queue.
     * Validates that neither user exceeds [maxActiveMatches] before creating.
     * The first ChatSession must be started separately via ChatService.startFirstChat().
     */
    fun createMatch(userAId: UUID, userBId: UUID): Match {

        userService.lockActiveUsersOrThrow(listOf(userAId, userBId),
            "Cannot create match: one or more users were not found")

        checkMatchLimit(userId = userAId)
        checkMatchLimit(userId = userBId)
        checkNotBlockedPair(
            userAId = userAId,
            userBId = userBId
        )

        val match = matchRepository.save(
            Match(
                userAId = userAId,
                userBId = userBId
            )
        )

        lockRepository.save(
            ActiveEngagementLock(
                userId = userAId,
                engagementId = match.id,
                engagementType = EngagementType.MATCH
            )
        )

        lockRepository.save(
            ActiveEngagementLock(
                userId = userBId,
                engagementId = match.id,
                engagementType = EngagementType.MATCH
            )
        )

        queueRepository.deleteByUserId(userId = userAId)
        queueRepository.deleteByUserId(userId = userBId)

        return match
    }

    private fun checkMatchLimit(userId: UUID) {

        val active = lockRepository.countByUserIdAndEngagementType(
            userId,
            EngagementType.MATCH
        )

        check(active < maxActiveMatches) {
            "User $userId has reached the maximum number of active matches ($maxActiveMatches)"
        }
    }

    private fun checkNotBlockedPair(
        userAId: UUID,
        userBId: UUID
    ) {
        if (userBlockService.isBlockedPair(userAId, userBId)) {
            throw DomainConflictException(
                code = DomainErrorCode.USER_PAIR_BLOCKED,
                message = "Cannot create match between users with an existing block"
            )
        }
    }

    private fun validateParticipant(
        match: Match,
        userId: UUID
    ) {
        if (userId != match.userAId && userId != match.userBId) {
            throw AccessDeniedException("User $userId does not belong to match ${match.id}")
        }
    }

    fun releaseMatchLockForUser(
        matchId: UUID,
        userId: UUID
    ) {
        lockRepository.deleteByUserIdAndEngagementId(
            userId = userId,
            engagementId = matchId
        )
    }

    /**
     * Called when both users approved the first chat.
     * Transitions state CHAT_ACTIVE -> VISUAL_PHASE directly.
     * The VisualReview must be initialized separately via VisualReviewService.initializeForMatch().
     */
    fun transitionToVisualPhase(matchId: UUID): Match {

        val match = findByIdOrThrow(matchId)

        if (match.state == MatchState.VISUAL_PHASE) {
            return match
        }

        check(match.state == MatchState.CHAT_ACTIVE) {
            "Cannot transition to visual phase: match is in state ${match.state}"
        }

        match.state = MatchState.VISUAL_PHASE
        match.updatedAt = OffsetDateTime.now()

        return matchRepository.save(match)
    }

    /**
     * Called when both users approved each other visually.
     * Transitions VISUAL_PHASE -> VISUAL_APPROVED.
     * Connection creation must be triggered separately via ConnectionService.createFromMatch().
     */
    fun approveVisualPhase(matchId: UUID): Match {

        val match = findByIdOrThrow(matchId)

        if (match.state == MatchState.VISUAL_APPROVED) {
            return match
        }

        check(match.state == MatchState.VISUAL_PHASE) {
            "Cannot approve visual phase: match is in state ${match.state}"
        }

        match.state = MatchState.VISUAL_APPROVED
        match.updatedAt = OffsetDateTime.now()

        return matchRepository.save(match)
    }

    /**
     * Called when at least one user rejected continuing after the first chat.
     * Transitions CHAT_ACTIVE -> CHAT_REJECTED and releases engagement locks.
     */
    fun rejectChatPhase(matchId: UUID): Match {

        val match = findByIdOrThrow(matchId)

        if (match.state == MatchState.CHAT_REJECTED) {
            return match
        }

        check(match.state == MatchState.CHAT_ACTIVE) {
            "Cannot reject chat phase: match is in state ${match.state}"
        }

        match.state = MatchState.CHAT_REJECTED
        match.updatedAt = OffsetDateTime.now()

        matchRepository.save(match)

        lockRepository.deleteByEngagementId(
            engagementId = matchId
        )

        return match
    }

    /**
     * Called when at least one user rejected the visual phase.
     * Transitions VISUAL_PHASE -> VISUAL_REJECTED and releases engagement locks.
     */
    fun rejectVisualPhase(matchId: UUID): Match {

        val match = findByIdOrThrow(matchId)

        if (match.state == MatchState.VISUAL_REJECTED) {
            return match
        }

        check(match.state == MatchState.VISUAL_PHASE) {
            "Cannot reject visual phase: match is in state ${match.state}"
        }

        match.state = MatchState.VISUAL_REJECTED
        match.updatedAt = OffsetDateTime.now()

        matchRepository.save(match)

        lockRepository.deleteByEngagementId(
            engagementId = matchId
        )

        return match
    }

    /**
     * Expires a match and releases engagement locks for both users.
     * Valid from CHAT_ACTIVE or VISUAL_PHASE states.
     */
    fun expireMatch(matchId: UUID): Boolean {

        val match = findByIdOrThrow(matchId)

        if (match.state == MatchState.EXPIRED) {
            return false
        }

        check(
            match.state == MatchState.CHAT_ACTIVE ||
                match.state == MatchState.VISUAL_PHASE
        ) {
            "Cannot expire match: match is in state ${match.state}"
        }

        match.state = MatchState.EXPIRED
        match.updatedAt = OffsetDateTime.now()

        matchRepository.save(match)

        lockRepository.deleteByEngagementId(
            engagementId = matchId
        )

        return true
    }
}
