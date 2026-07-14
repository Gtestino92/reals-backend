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
import java.time.OffsetDateTime
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
     * Claims one eligible anchor queue row, scores a bounded partner window,
     * then claims partners one at a time with SKIP LOCKED fallback.
     *
     * Actual match creation is delegated to MatchService.
     *
     * The repository applies hard SQL filters before partner LIMIT, including
     * exact mutual distance. SearchLocationMatchFilter remains as a defensive
     * service-layer parity check. CompatibilityScorer ranks the remaining
     * candidates without changing early-accept FIFO semantics.
     */
    fun claimNextCandidatePair(): Pair<UUID, UUID>? {
        require(candidatePairLimit > 0) {
            "Candidate partner limit must be greater than 0"
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
        val now = OffsetDateTime.now()
        val exclusionPolicy = matchmakingPairEligibilityService.effectiveExclusionPolicy()
        val cutoffs = historicalCutoffs(now)

        val anchor =
            candidateRepository.claimNextEligibleAnchorForUpdate(
                today = today,
                exclusionPolicy = exclusionPolicy,
                previousPairingCutoff = cutoffs.previousPairingCutoff,
                firstChatExpirationCutoff = cutoffs.firstChatExpirationCutoff
            )
                ?: return null

        val partnerCandidates =
            candidateRepository.findEligiblePartnerCandidates(
                anchorQueueEntryId = anchor.queueEntryId,
                limit = candidatePairLimit,
                today = today,
                exclusionPolicy = exclusionPolicy,
                previousPairingCutoff = cutoffs.previousPairingCutoff,
                firstChatExpirationCutoff = cutoffs.firstChatExpirationCutoff
            )

        val claimAttempts = rankPartnerClaimAttempts(partnerCandidates)

        for (claimAttempt in claimAttempts) {
            val claimed =
                candidateRepository.tryClaimEligiblePartnerForUpdate(
                    anchorQueueEntryId = anchor.queueEntryId,
                    partnerQueueEntryId = claimAttempt.candidate.partnerQueueEntryId,
                    today = today,
                    exclusionPolicy = exclusionPolicy,
                    previousPairingCutoff = cutoffs.previousPairingCutoff,
                    firstChatExpirationCutoff = cutoffs.firstChatExpirationCutoff
                )

            if (claimed != null) {
                return Pair(
                    claimed.pair.userAId,
                    claimed.pair.userBId
                )
            }
        }

        return null
    }

    private fun rankPartnerClaimAttempts(
        partnerCandidates: List<MatchmakingPartnerCandidate>
    ): List<ScoredMatchmakingPartnerCandidate> {
        val candidateUserIds =
            partnerCandidates
                .flatMap { listOf(it.pair.userAId, it.pair.userBId) }
                .distinct()

        val profilesByUserId =
            profileService
                .findByUserIds(candidateUserIds)
                .associateBy { it.userId }

        val earlyAccepted = mutableListOf<ScoredMatchmakingPartnerCandidate>()
        val fallback = mutableListOf<ScoredMatchmakingPartnerCandidate>()

        for ((index, candidate) in partnerCandidates.withIndex()) {
            val pair = candidate.pair
            val profileA = profilesByUserId[pair.userAId]
                ?: continue
            val profileB = profilesByUserId[pair.userBId]
                ?: continue

            val scoredCandidate =
                scoreCandidatePair(
                    candidate = candidate,
                    order = index,
                    profileA = profileA,
                    profileB = profileB
                ) ?: continue

            if (scoredCandidate.score >= earlyAcceptCompatibilityScore) {
                earlyAccepted.add(scoredCandidate)
            } else {
                fallback.add(scoredCandidate)
            }
        }

        return earlyAccepted +
            fallback.sortedWith(
                compareByDescending<ScoredMatchmakingPartnerCandidate> { it.score }
                    .thenBy { it.order }
            )
    }

    private fun scoreCandidatePair(
        candidate: MatchmakingPartnerCandidate,
        order: Int,
        profileA: Profile,
        profileB: Profile
    ): ScoredMatchmakingPartnerCandidate? {
        val pair = candidate.pair
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

        return ScoredMatchmakingPartnerCandidate(
            candidate = candidate,
            score = score,
            order = order
        )
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

    private fun historicalCutoffs(now: OffsetDateTime): MatchmakingHistoricalCutoffs {
        val includeHistoricalExclusion =
            matchmakingPairEligibilityService.effectiveExclusionPolicy().excludeHistoricalPairings
        return MatchmakingHistoricalCutoffs(
            previousPairingCutoff =
                if (includeHistoricalExclusion) {
                    matchmakingPairEligibilityService.previousPairingCutoff(now)
                } else {
                    null
                },
            firstChatExpirationCutoff =
                if (includeHistoricalExclusion) {
                    matchmakingPairEligibilityService.firstChatExpirationCutoff(now)
                } else {
                    null
                }
        )
    }

    private data class MatchmakingHistoricalCutoffs(
        val previousPairingCutoff: OffsetDateTime?,
        val firstChatExpirationCutoff: OffsetDateTime?
    )

    private data class ScoredMatchmakingPartnerCandidate(
        val candidate: MatchmakingPartnerCandidate,
        val score: Double,
        val order: Int
    )
}
