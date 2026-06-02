package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.matching.CompatibilityScorer
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Service
@Transactional
class MatchmakingService(

    private val queueRepository: MatchmakingQueueRepository,
    private val lockRepository: ActiveEngagementLockRepository,
    private val userRepository: UserRepository,
    private val penaltyService: PenaltyService,
    private val profileService: ProfileService,
    private val compatibilityScorer: CompatibilityScorer,

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

        check(activeMatches < maxActiveMatches) {
            "User $userId has reached the maximum number of active matches ($maxActiveMatches)"
        }

        check(!penaltyService.hasActivePenalty(userId)) {
            "User $userId has an active penalty"
        }

        val profile = profileService.findByUserId(userId)
            ?: error("User $userId does not have a profile")

        check(profileService.isEligibleForMatchmaking(profile.id)) {
            "User $userId profile is not active — complete and submit your profile first"
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
        check(userRepository.findAllByIdForUpdate(listOf(userId)).size == 1) {
            "Cannot enqueue: user $userId was not found"
        }
    }

    fun dequeue(userId: UUID) {
        queueRepository.deleteByUserId(userId)
    }

    /**
     * Claims basic-compatible queue pairs using SKIP LOCKED and selects the
     * best scored pair.
     *
     * Actual match creation is delegated to MatchService.
     *
     * The repository query applies SQL-compatible filters such as active
     * profile, mutual gender preference, intention and mutual preferred age
     * range. CompatibilityScorer is the extension point for application-level
     * filters and richer scoring.
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

        candidatePairs.forEachIndexed { index, pair ->
            val profileA = profilesByUserId[pair.userAId]
            val profileB = profilesByUserId[pair.userBId]

            if (profileA != null && profileB != null) {
                if (!distancePreferenceOk(pair, profileA, profileB)) {
                    return@forEachIndexed
                }

                val score = compatibilityScorer.score(profileA, profileB)

                if (score >= earlyAcceptCompatibilityScore) {
                    return pair
                }

                if (score >= minCompatibilityScore) {
                    val scoredCandidate =
                        ScoredMatchmakingCandidatePair(
                            pair = pair,
                            score = score,
                            order = index
                        )

                    val currentBest = bestCandidate
                    if (
                        currentBest == null ||
                        scoredCandidate.score > currentBest.score ||
                        (
                            scoredCandidate.score == currentBest.score &&
                                scoredCandidate.order < currentBest.order
                            )
                    ) {
                        bestCandidate = scoredCandidate
                    }
                }
            }
        }

        return bestCandidate?.pair
    }

    private fun validateSearchLocation(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Int?
    ) {
        require(latitude in -90.0..90.0) {
            "Latitude must be between -90 and 90"
        }
        require(longitude in -180.0..180.0) {
            "Longitude must be between -180 and 180"
        }
        accuracyMeters?.let {
            require(it in 0..100000) {
                "Location accuracy must be between 0 and 100000 meters"
            }
        }
    }

    private fun distancePreferenceOk(
        pair: MatchmakingCandidatePair,
        profileA: Profile,
        profileB: Profile
    ): Boolean {
        val distanceKm = distanceKm(
            latitudeA = pair.userALatitude,
            longitudeA = pair.userALongitude,
            latitudeB = pair.userBLatitude,
            longitudeB = pair.userBLongitude
        )

        return distanceKm <= profileA.maxDistanceKm &&
            distanceKm <= profileB.maxDistanceKm
    }

    private fun distanceKm(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double
    ): Double {
        val radiusKm = 6371.0
        val deltaLatitude = degreesToRadians(latitudeB - latitudeA)
        val deltaLongitude = degreesToRadians(longitudeB - longitudeA)
        val latA = degreesToRadians(latitudeA)
        val latB = degreesToRadians(latitudeB)

        val haversine =
            sin(deltaLatitude / 2).pow(2) +
                cos(latA) * cos(latB) * sin(deltaLongitude / 2).pow(2)

        val normalizedHaversine = haversine.coerceIn(0.0, 1.0)

        return radiusKm * 2 * atan2(
            sqrt(normalizedHaversine),
            sqrt(1 - normalizedHaversine)
        )
    }

    private fun degreesToRadians(value: Double): Double =
        value * PI / 180.0
}
