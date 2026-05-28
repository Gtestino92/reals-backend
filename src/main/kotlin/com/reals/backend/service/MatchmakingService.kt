package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.service.matching.CompatibilityEvaluator
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*

@Service
@Transactional
class MatchmakingService(

    private val queueRepository: MatchmakingQueueRepository,
    private val lockRepository: ActiveEngagementLockRepository,
    private val penaltyService: PenaltyService,
    private val profileService: ProfileService,
    private val compatibilityEvaluator: CompatibilityEvaluator,

    @param:Value("\${engagement.max-active-matches:5}")
    private val maxActiveMatches: Int

) {

    /**
     * Adds a user to the matchmaking queue.
     * Preconditions:
     *  - active match count < maxActiveMatches (condigurable, default 5)
     *  - no active penalty
     *  - profile is ACTIVE (photo validation already happened at profile activation)
     */
    fun enqueue(userId: UUID) {

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

        if (queueRepository.existsByUserId(userId)) {
            return
        }

        queueRepository.save(
            MatchmakingQueueEntry(userId = userId)
        )
    }

    fun dequeue(userId: UUID) {
        queueRepository.deleteByUserId(userId)
    }

    /**
     * Selects candidate pairs from the queue using SKIP LOCKED.
     * Actual Match creation is delegated to MatchService.
     * TODO (compatibility-filter): implement basic compatibility before going to prod
     * Current behavior: pairs any two users regardless of preferences
     * Minimum required:
     *  1. Gender match: profileA.gender must satisfy profileB.lookingForGender and vice versa
     *  2. Same city or country (geographic proximity).
     *     Use Profile.city first, fallback to Profile.country
     *     Then some geolocalization could be implemented
     *  3. Same intention (DATE/FRIENDSHIP/CASUAL) - no point matching different ones
     *  4. Interest/affinities - add 'interests' field (list of tags) to Profile,
     *      prioritize pairs with the highest tag overlap.
     *      NOTE: do NOT use ELO score - it rewards popularity and creates implicit hierarchies
     *      which goes against the spirit of this app (genuine connection, anonymous-first).
     *  5. Age range - configurable tolerance, e.g. +-5 years by default
     *
     * Implementation hint: join MATCHMAKING_QUEUE_ENTRY with PROFILE on userId,
     * then filter pairs in-memory or via a dedicated SQL query with cross join + WHERE
     */
    fun findCandidatePairs(batchSize: Int): List<Pair<UUID, UUID>> {

        val candidates = queueRepository
            .findWaitingSkipLocked(batchSize * 4)
            .toMutableList()

        val pairs = mutableListOf<Pair<UUID, UUID>>()

        while (candidates.size >= 2 && pairs.size < batchSize) {

            val a = candidates.removeAt(0)

            val profileA = profileService.findByUserId(a.userId)
                ?: continue

            val matchedEntry = candidates.firstOrNull { b ->

                val profileB = profileService.findByUserId(b.userId)

                profileB != null &&
                    compatibilityEvaluator.compatible(profileA, profileB)
            }

            if (matchedEntry != null) {

                candidates.remove(matchedEntry)

                pairs.add(
                    Pair(
                        a.userId,
                        matchedEntry.userId
                    )
                )
            }
        }

        return pairs
    }
}
