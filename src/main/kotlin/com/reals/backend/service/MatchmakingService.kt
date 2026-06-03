package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.matching.CompatibilityScorer
import com.reals.backend.service.matching.SearchLocationMatchFilter
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.*

@Service
@Transactional
class MatchmakingService(

    private val queueRepository: MatchmakingQueueRepository,
    private val lockRepository: ActiveEngagementLockRepository,
    private val userRepository: UserRepository,
    private val penaltyService: PenaltyService,
    private val profileService: ProfileService,
    private val compatibilityScorer: CompatibilityScorer,
    private val searchLocationMatchFilter: SearchLocationMatchFilter,

    @param:Value("\${engagement.max-active-matches:5}")
    private val maxActiveMatches: Int,

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

        lockUser(userId)
        validateSearchLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters
        )

        val activeMatches = lockRepository.countByUserIdAndEngagementType(
            userId,
            EngagementType.MATCH
        )

        if (activeMatches >= maxActiveMatches) {
            throw DomainConflictException(
                code = DomainErrorCode.ACTIVE_MATCH_LIMIT_REACHED,
                message = "User has reached the maximum number of active matches ($maxActiveMatches)"
            )
        }

        if (penaltyService.hasActivePenalty(userId)) {
            throw DomainConflictException(
                code = DomainErrorCode.ACTIVE_PENALTY,
                message = "User has an active penalty"
            )
        }

        val profile = profileService.findByUserId(userId)
            ?: throw DomainConflictException(
                code = DomainErrorCode.PROFILE_REQUIRED,
                message = "User must create a profile before entering matchmaking"
            )

        if (!profileService.isEligibleForMatchmaking(profile.id)) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_NOT_ACTIVE,
                message = "Profile must be active before entering matchmaking"
            )
        }

        val existingQueueEntry = queueRepository.findByUserId(userId)
        if (existingQueueEntry != null) {
            existingQueueEntry.latitude = latitude
            existingQueueEntry.longitude = longitude
            existingQueueEntry.accuracyMeters = accuracyMeters
            queueRepository.save(existingQueueEntry)
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
    }

    private fun lockUser(userId: UUID) {
        if (userRepository.findAllByIdForUpdate(listOf(userId)).size != 1) {
            throw DomainConflictException(
                code = DomainErrorCode.USER_NOT_FOUND,
                message = "Cannot enqueue: user was not found"
            )
        }
    }

    fun dequeue(userId: UUID) {
        queueRepository.deleteByUserId(userId)
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
        val candidatePairs =
            queueRepository
                .findBasicCompatiblePairsSkipLocked(
                    limit = candidatePairLimit,
                    today = today
                )
                .map {
                    MatchmakingCandidatePair(
                        userAId = UUID.fromString(it.userAId),
                        userBId = UUID.fromString(it.userBId),
                        userALatitude = it.userALatitude,
                        userALongitude = it.userALongitude,
                        userBLatitude = it.userBLatitude,
                        userBLongitude = it.userBLongitude
                    )
                }

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

        val score = compatibilityScorer.score(profileA, profileB)
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
