package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.engagement.EngagementCapacityAdmissionService
import com.reals.backend.service.engagement.EngagementCapacityEvaluationPhase
import com.reals.backend.service.matching.MatchmakingPairEligibilityService
import com.reals.backend.service.matching.VisualAdvancementCapService
import jakarta.transaction.Transactional
import org.springframework.security.access.AccessDeniedException
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
    private val matchmakingPairEligibilityService: MatchmakingPairEligibilityService,
    private val visualAdvancementCapService: VisualAdvancementCapService,
    private val engagementCapacityAdmissionService: EngagementCapacityAdmissionService,
    private val homeStateInvalidationService: HomeStateInvalidationService

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
     * Validates that neither user exceeds their effective admission capacity before creating.
     * The first ChatSession must be started separately via ChatService.startFirstChat().
     */
    fun createMatch(userAId: UUID, userBId: UUID): Match {
        val now = OffsetDateTime.now()

        userService.lockActiveUsersOrThrow(listOf(userAId, userBId),
            "Cannot create match: one or more users were not found")

        userBlockService.requirePairNotBlocked(
            userAId = userAId,
            userBId = userBId
        )
        matchmakingPairEligibilityService.requirePairCanCreateMatch(
            userAId = userAId,
            userBId = userBId,
            now = now
        )
        requireVisualAdvancementCapacity(userId = userAId, now = now)
        requireVisualAdvancementCapacity(userId = userBId, now = now)
        engagementCapacityAdmissionService.requireUsersCanReceiveNewMatch(
            userIds = listOf(userAId, userBId),
            now = now,
            phase = EngagementCapacityEvaluationPhase.FINAL_MATCH_ADMISSION
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
        homeStateInvalidationService.bumpBoth(
            userAId = userAId,
            userBId = userBId,
            reason = "match_created"
        )

        return match
    }

    private fun requireVisualAdvancementCapacity(userId: UUID, now: OffsetDateTime) {
        val status = visualAdvancementCapService.statusFor(userId = userId, now = now)
        if (status.blocked) {
            throw DomainConflictException(
                code = DomainErrorCode.VISUAL_ADVANCEMENT_LIMIT_REACHED,
                message = "User has reached the Visual Review advancement limit"
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
        userBlockService.requirePairNotBlocked(match.userAId, match.userBId)

        if (match.state == MatchState.VISUAL_PHASE) {
            return match
        }

        check(match.state == MatchState.CHAT_ACTIVE) {
            "Cannot transition to visual phase: match is in state ${match.state}"
        }

        match.state = MatchState.VISUAL_PHASE
        match.updatedAt = OffsetDateTime.now()

        val saved = matchRepository.save(match)
        homeStateInvalidationService.bumpBoth(
            userAId = saved.userAId,
            userBId = saved.userBId,
            reason = "match_visual_phase"
        )
        return saved
    }

    /**
     * Called when both users approved each other visually.
     * Transitions VISUAL_PHASE -> VISUAL_APPROVED.
     * Connection creation must be triggered separately via ConnectionService.createFromMatch().
     */
    fun approveVisualPhase(matchId: UUID): Match {

        val match = findByIdOrThrow(matchId)
        userBlockService.requirePairNotBlocked(match.userAId, match.userBId)

        if (match.state == MatchState.VISUAL_APPROVED) {
            return match
        }

        check(match.state == MatchState.VISUAL_PHASE) {
            "Cannot approve visual phase: match is in state ${match.state}"
        }

        match.state = MatchState.VISUAL_APPROVED
        match.updatedAt = OffsetDateTime.now()

        val saved = matchRepository.save(match)
        homeStateInvalidationService.bumpBoth(
            userAId = saved.userAId,
            userBId = saved.userBId,
            reason = "match_visual_approved"
        )
        return saved
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

        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "match_chat_rejected"
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

        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "match_visual_rejected"
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

        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "match_expired"
        )

        return true
    }
}
