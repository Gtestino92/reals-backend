package com.reals.backend.service.matching

import com.reals.backend.domain.*
import com.reals.backend.repository.matching.MatchmakingCandidateRepository
import com.reals.backend.service.HomeStateInvalidationService
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.service.ProfileService
import com.reals.backend.service.UserService
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.reliability.UserReliabilityScoreService
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.*

@Service
@Transactional
class MatchmakingService(

    private val queueRepository: MatchmakingQueueRepository,
    private val candidateRepository: MatchmakingCandidateRepository,
    private val userService: UserService,
    private val profileService: ProfileService,
    private val matchmakingAvailabilityService: MatchmakingAvailabilityService,
    private val compatibilityScorer: CompatibilityScorer,
    private val searchLocationMatchFilter: SearchLocationMatchFilter,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    private val userReliabilityScoreService: UserReliabilityScoreService,
    private val matchmakingPairEligibilityService: MatchmakingPairEligibilityService,

    @param:Value("\${matchmaking.candidate-pair-limit:50}")
    private val candidatePairLimit: Int,

    @param:Value("\${matchmaking.min-compatibility-score:0.2}")
    private val minCompatibilityScore: Double,

    @param:Value("\${matchmaking.early-accept-compatibility-score:0.9}")
    private val earlyAcceptCompatibilityScore: Double

) {
    /**
     * Adds a user to the matchmaking queue.
     * Preconditions:
     *  - active match count < maxActiveMatches (configurable, default 5)
     *  - active connection count < maxActiveConnections (configurable, default 2)
     *  - no active penalty
     *  - profile is ACTIVE (photo validation already happened at profile activation)
     *  - current search location is present and valid
     */
    fun enqueue(
        userId: UUID,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Int? = null
    ) {

        userService.lockActiveUserOrThrow(userId, "Cannot add in queue: user not found")

        validateSearchLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters
        )

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId)
        availability.blockedReason?.let { blockedReason ->
            throw DomainConflictException(
                code = DomainErrorCode.valueOf(blockedReason.code),
                message = blockedReason.message
            )
        }

        val existingQueueEntry = queueRepository.findByUserId(userId)
        if (existingQueueEntry != null) {
            existingQueueEntry.latitude = latitude
            existingQueueEntry.longitude = longitude
            existingQueueEntry.accuracyMeters = accuracyMeters
            queueRepository.save(existingQueueEntry)
            homeStateInvalidationService.bump(
                userId = userId,
                reason = "matchmaking_queue_updated"
            )
            return
        }

        queueRepository.save(
            MatchmakingQueueEntry(
                userId = userId,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracyMeters
            )
        )
        homeStateInvalidationService.bump(
            userId = userId,
            reason = "matchmaking_queue_entered"
        )
    }

    fun dequeue(userId: UUID) {
        val wasQueued = queueRepository.existsByUserId(userId)
        queueRepository.deleteByUserId(userId)
        if (wasQueued) {
            homeStateInvalidationService.bump(
                userId = userId,
                reason = "matchmaking_queue_left"
            )
        }
    }

    /**
     * Claims hard-filtered queue pairs using SKIP LOCKED and selects the
     * best scored pair.
     *
     * Actual match creation is delegated to MatchService.
     *
     * The repository query applies hard filters that can run in SQL, such as
     * active profile, mutual gender preference, intention and mutual preferred
     * age range. SearchLocationMatchFilter applies the application-level
     * distance hard filter. CompatibilityScorer ranks the remaining candidates.
     */
    fun findNextCandidatePair(): Pair<UUID, UUID>? {
        require(candidatePairLimit > 0) {
            "Candidate pair limit must be greater than 0"
        }
        require(minCompatibilityScore in 0.0..1.0) {
            "Minimum compatibility score must be between 0.0 and 1.0"
        }
        require(earlyAcceptCompatibilityScore in 0.0..1.0) {
            "Early accept compatibility score must be between 0.0 and 1.0"
        }
        require(earlyAcceptCompatibilityScore >= minCompatibilityScore) {
            "Early accept compatibility score must be greater than or equal to minimum compatibility score"
        }

        val today = LocalDate.now()
        val now = java.time.OffsetDateTime.now()
        val includeHistoricalExclusion = matchmakingPairEligibilityService.isHistoricalExclusionEnabled()
        val previousPairingCutoff =
            if (includeHistoricalExclusion) {
                matchmakingPairEligibilityService.previousPairingCutoff(now)
            } else {
                null
            }
        val firstChatExpirationCutoff =
            if (includeHistoricalExclusion) {
                matchmakingPairEligibilityService.firstChatExpirationCutoff(now)
            } else {
                null
            }
        val candidatePairs =
            candidateRepository.findEligibleCandidatePairsForUpdate(
                limit = candidatePairLimit,
                today = today,
                previousPairingCutoff = previousPairingCutoff,
                firstChatExpirationCutoff = firstChatExpirationCutoff
            )

        if (candidatePairs.isEmpty()) {
            return null
        }

        val selectedPair =
            selectBestCandidatePair(candidatePairs)
                ?: return null

        return Pair(
            selectedPair.userAId,
            selectedPair.userBId
        )
    }

    private fun selectBestCandidatePair(
        candidatePairs: List<MatchmakingCandidatePair>
    ): MatchmakingCandidatePair? {
        val candidateUserIds =
            candidatePairs
                .flatMap { listOf(it.userAId, it.userBId) }
                .distinct()

        val profilesByUserId =
            profileService
                .findByUserIds(candidateUserIds)
                .associateBy { it.userId }

        var bestCandidate: ScoredMatchmakingCandidatePair? = null

        for ((index, pair) in candidatePairs.withIndex()) {
            val profileA = profilesByUserId[pair.userAId]
                ?: continue
            val profileB = profilesByUserId[pair.userBId]
                ?: continue

            val scoredCandidate =
                scoreCandidatePair(
                    pair = pair,
                    order = index,
                    profileA = profileA,
                    profileB = profileB
                ) ?: continue

            if (scoredCandidate.score >= earlyAcceptCompatibilityScore) {
                return scoredCandidate.pair
            }

            if (isBetterCandidate(scoredCandidate, bestCandidate)) {
                bestCandidate = scoredCandidate
            }
        }

        return bestCandidate?.pair
    }

    private fun scoreCandidatePair(
        pair: MatchmakingCandidatePair,
        order: Int,
        profileA: Profile,
        profileB: Profile
    ): ScoredMatchmakingCandidatePair? {
        if (!searchLocationMatchFilter.passes(pair, profileA, profileB)) {
            return null
        }

        val compatibilityScore = compatibilityScorer.score(profileA, profileB)
        val score =
            if (userReliabilityScoreService.enabled) {
                (compatibilityScore + userReliabilityScoreService.matchmakingModifierForPair(
                    userAId = pair.userAId,
                    userBId = pair.userBId
                )).coerceIn(0.0, 1.0)
            } else {
                compatibilityScore
            }
        if (score < minCompatibilityScore) {
            return null
        }

        return ScoredMatchmakingCandidatePair(
            pair = pair,
            score = score,
            order = order
        )
    }

    private fun isBetterCandidate(
        candidate: ScoredMatchmakingCandidatePair,
        currentBest: ScoredMatchmakingCandidatePair?
    ): Boolean {
        if (currentBest == null) {
            return true
        }

        if (candidate.score != currentBest.score) {
            return candidate.score > currentBest.score
        }

        return candidate.order < currentBest.order
    }

    private fun validateSearchLocation(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Int?
    ) {
        if (latitude !in -90.0..90.0) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_SEARCH_LOCATION,
                message = "Latitude must be between -90 and 90"
            )
        }
        if (longitude !in -180.0..180.0) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_SEARCH_LOCATION,
                message = "Longitude must be between -180 and 180"
            )
        }
        accuracyMeters?.let {
            if (it !in 0..100000) {
                throw DomainBadRequestException(
                    code = DomainErrorCode.INVALID_SEARCH_LOCATION,
                    message = "Location accuracy must be between 0 and 100000 meters"
                )
            }
        }
    }
}
