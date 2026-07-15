package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingRankingMode
import com.reals.backend.config.MatchmakingRankingProperties
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.MatchmakingAnchor
import com.reals.backend.domain.MatchmakingCandidatePair
import com.reals.backend.domain.MatchmakingPartnerCandidate
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.matching.MatchmakingCandidateRepository
import com.reals.backend.repository.matching.MatchmakingPairExclusionPolicy
import com.reals.backend.service.HomeStateInvalidationService
import com.reals.backend.service.ProfileService
import com.reals.backend.service.UserService
import com.reals.backend.service.reliability.UserReliabilityScoreService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class MatchmakingServiceRankingTest {

    private val anchorUserId = UUID.nameUUIDFromBytes("anchor".toByteArray())
    private val partnerAUserId = UUID.nameUUIDFromBytes("partner-a".toByteArray())
    private val partnerBUserId = UUID.nameUUIDFromBytes("partner-b".toByteArray())
    private val partnerCUserId = UUID.nameUUIDFromBytes("partner-c".toByteArray())

    @Test
    fun `probabilistic ranking loads reliability scores once and never calls pair modifier`() {
        val candidateRepository =
            FakeCandidateRepository(
                candidates = listOf(
                    candidate("a", partnerAUserId),
                    candidate("b", partnerBUserId),
                    candidate("c", partnerCUserId)
                )
            )
        candidateRepository.claimResults[candidateRepository.candidates.first().partnerQueueEntryId] =
            candidateRepository.candidates.first()
        val reliabilityService = reliabilityService(enabled = true)
        Mockito.`when`(
            reliabilityService.effectiveScores(anyUuidCollection(), anyOffsetDateTime())
        ).thenReturn(
            mapOf(
                anchorUserId to 100.0,
                partnerAUserId to 100.0,
                partnerBUserId to 95.0,
                partnerCUserId to 90.0
            )
        )

        service(
            mode = MatchmakingRankingMode.PROBABILISTIC_WEIGHTED,
            candidateRepository = candidateRepository,
            reliabilityService = reliabilityService,
            randomValues = listOf(0.5, 0.5, 0.5)
        ).claimNextCandidatePair()

        @Suppress("UNCHECKED_CAST")
        val userIdsCaptor =
            ArgumentCaptor.forClass(Collection::class.java) as ArgumentCaptor<Collection<UUID>>
        Mockito.verify(reliabilityService).effectiveScores(captureUuidCollection(userIdsCaptor), anyOffsetDateTime())
        assertEquals(setOf(anchorUserId, partnerAUserId, partnerBUserId, partnerCUserId), userIdsCaptor.value.toSet())
        Mockito.verify(reliabilityService, Mockito.never()).matchmakingModifierForPair(
            anyUuid(),
            anyUuid(),
            anyOffsetDateTime()
        )
    }

    @Test
    fun `probabilistic claim miss advances to next ordered candidate`() {
        val first = candidate("first", partnerAUserId)
        val second = candidate("second", partnerBUserId)
        val candidateRepository =
            FakeCandidateRepository(
                candidates = listOf(first, second)
            )
        candidateRepository.claimResults[first.partnerQueueEntryId] = null
        candidateRepository.claimResults[second.partnerQueueEntryId] = second
        val reliabilityService = reliabilityService(enabled = false)

        val pair =
            service(
                mode = MatchmakingRankingMode.PROBABILISTIC_WEIGHTED,
                candidateRepository = candidateRepository,
                reliabilityService = reliabilityService,
                randomValues = listOf(0.5, 0.5)
            ).claimNextCandidatePair()

        assertEquals(Pair(anchorUserId, partnerBUserId), pair)
        assertEquals(listOf(first.partnerQueueEntryId, second.partnerQueueEntryId), candidateRepository.claimAttempts)
        Mockito.verify(reliabilityService, Mockito.never()).effectiveScores(anyUuidCollection(), anyOffsetDateTime())
    }

    @Test
    fun `legacy mode keeps first fifo early accepted candidate first`() {
        val first = candidate("first", partnerAUserId)
        val second = candidate("second", partnerBUserId)
        val candidateRepository =
            FakeCandidateRepository(
                candidates = listOf(first, second)
            )
        candidateRepository.claimResults[first.partnerQueueEntryId] = first
        val reliabilityService = reliabilityService(enabled = false)

        val pair =
            service(
                mode = MatchmakingRankingMode.LEGACY_EARLY_ACCEPT,
                candidateRepository = candidateRepository,
                reliabilityService = reliabilityService,
                randomValues = emptyList()
            ).claimNextCandidatePair()

        assertEquals(Pair(anchorUserId, partnerAUserId), pair)
        assertEquals(listOf(first.partnerQueueEntryId), candidateRepository.claimAttempts)
    }

    @Test
    fun `legacy mode keeps score descending fallback with fifo tie break`() {
        val lower = candidate("lower", partnerAUserId)
        val higher = candidate("higher", partnerBUserId)
        val candidateRepository =
            FakeCandidateRepository(
                candidates = listOf(lower, higher)
            )
        candidateRepository.claimResults[higher.partnerQueueEntryId] = higher
        val reliabilityService = reliabilityService(enabled = true)
        Mockito.`when`(
            reliabilityService.effectiveScores(anyUuidCollection(), anyOffsetDateTime())
        ).thenReturn(
            mapOf(
                anchorUserId to 100.0,
                partnerAUserId to 80.0,
                partnerBUserId to 120.0
            )
        )
        Mockito.`when`(reliabilityService.matchmakingModifierForScores(100.0, 80.0)).thenReturn(-0.05)
        Mockito.`when`(reliabilityService.matchmakingModifierForScores(100.0, 120.0)).thenReturn(0.05)

        val pair =
            service(
                mode = MatchmakingRankingMode.LEGACY_EARLY_ACCEPT,
                candidateRepository = candidateRepository,
                reliabilityService = reliabilityService,
                randomValues = emptyList(),
                compatibilityScore = 0.5,
                earlyAcceptCompatibilityScore = 0.9
            ).claimNextCandidatePair()

        assertEquals(Pair(anchorUserId, partnerBUserId), pair)
        assertEquals(listOf(higher.partnerQueueEntryId), candidateRepository.claimAttempts)
        Mockito.verify(reliabilityService, Mockito.never()).matchmakingModifierForPair(
            anyUuid(),
            anyUuid(),
            anyOffsetDateTime()
        )
    }

    private fun service(
        mode: MatchmakingRankingMode,
        candidateRepository: FakeCandidateRepository,
        reliabilityService: UserReliabilityScoreService,
        randomValues: List<Double>,
        compatibilityScore: Double = 1.0,
        earlyAcceptCompatibilityScore: Double = 0.9
    ): MatchmakingService {
        val profileService = Mockito.mock(ProfileService::class.java)
        Mockito.`when`(profileService.findByUserIds(anyUuidCollection()))
            .thenReturn(
                listOf(
                    profile(anchorUserId, Gender.FEMALE, Gender.MALE),
                    profile(partnerAUserId, Gender.MALE, Gender.FEMALE),
                    profile(partnerBUserId, Gender.MALE, Gender.FEMALE),
                    profile(partnerCUserId, Gender.MALE, Gender.FEMALE)
                )
            )
        val pairEligibilityService = Mockito.mock(MatchmakingPairEligibilityService::class.java)
        Mockito.`when`(pairEligibilityService.effectiveExclusionPolicy())
            .thenReturn(MatchmakingPairExclusionPolicy.ACTIVE_ONLY)
        val compatibilityScorer =
            object : CompatibilityScorer {
                override fun score(
                    profileA: Profile,
                    profileB: Profile
                ): Double =
                    compatibilityScore
            }
        val rankingProperties =
            MatchmakingRankingProperties(
                mode = mode,
                compatibilityTemperature = 0.20,
                reliabilitySimilarityScale = 10.0,
                waitingRelaxationPeriodHours = 72.0,
                maximumSimilarityScaleMultiplier = 3.0
            )

        return MatchmakingService(
            queueRepository = Mockito.mock(MatchmakingQueueRepository::class.java),
            candidateRepository = candidateRepository,
            userService = Mockito.mock(UserService::class.java),
            profileService = profileService,
            matchmakingAvailabilityService = Mockito.mock(MatchmakingAvailabilityService::class.java),
            compatibilityScorer = compatibilityScorer,
            searchLocationMatchFilter = SearchLocationMatchFilter(),
            homeStateInvalidationService = Mockito.mock(HomeStateInvalidationService::class.java),
            userReliabilityScoreService = reliabilityService,
            matchmakingPairEligibilityService = pairEligibilityService,
            rankingProperties = rankingProperties,
            probabilisticWeightPolicy = ProbabilisticMatchmakingWeightPolicy(rankingProperties),
            weightedCandidateOrderer = WeightedMatchmakingCandidateOrderer(sequenceRandom(randomValues)),
            candidatePairLimit = 10,
            minCompatibilityScore = 0.2,
            earlyAcceptCompatibilityScore = earlyAcceptCompatibilityScore
        )
    }

    private fun reliabilityService(enabled: Boolean): UserReliabilityScoreService {
        val service = Mockito.mock(UserReliabilityScoreService::class.java)
        Mockito.`when`(service.enabled).thenReturn(enabled)
        return service
    }

    private fun sequenceRandom(values: List<Double>): MatchmakingRandomSource {
        val iterator = values.iterator()
        return MatchmakingRandomSource {
            if (iterator.hasNext()) {
                iterator.next()
            } else {
                0.5
            }
        }
    }

    private fun candidate(
        label: String,
        partnerUserId: UUID
    ): MatchmakingPartnerCandidate =
        MatchmakingPartnerCandidate(
            partnerQueueEntryId = UUID.nameUUIDFromBytes("queue-$label".toByteArray()),
            partnerEnteredAt = OffsetDateTime.parse("2026-07-14T11:00:00Z"),
            pair = MatchmakingCandidatePair(
                userAId = anchorUserId,
                userBId = partnerUserId,
                userALatitude = -34.6037,
                userALongitude = -58.3816,
                userBLatitude = -34.6037,
                userBLongitude = -58.3816
            )
        )

    private fun profile(
        userId: UUID,
        gender: Gender,
        lookingFor: Gender
    ): Profile =
        Profile(
            userId = userId,
            displayName = "Profile",
            birthDate = LocalDate.now().minusYears(30),
            gender = gender,
            lookingForGenders = mutableSetOf(lookingFor),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            status = ProfileStatus.ACTIVE
        )

    private class FakeCandidateRepository(
        val candidates: List<MatchmakingPartnerCandidate>
    ) : MatchmakingCandidateRepository {

        val claimResults = mutableMapOf<UUID, MatchmakingPartnerCandidate?>()
        val claimAttempts = mutableListOf<UUID>()

        override fun claimNextEligibleAnchorForUpdate(
            today: LocalDate,
            exclusionPolicy: MatchmakingPairExclusionPolicy,
            previousPairingCutoff: OffsetDateTime?,
            firstChatExpirationCutoff: OffsetDateTime?
        ): MatchmakingAnchor =
            MatchmakingAnchor(
                queueEntryId = UUID.nameUUIDFromBytes("anchor-queue".toByteArray()),
                userId = UUID.nameUUIDFromBytes("anchor".toByteArray())
            )

        override fun findEligiblePartnerCandidates(
            anchorQueueEntryId: UUID,
            limit: Int,
            today: LocalDate,
            exclusionPolicy: MatchmakingPairExclusionPolicy,
            previousPairingCutoff: OffsetDateTime?,
            firstChatExpirationCutoff: OffsetDateTime?
        ): List<MatchmakingPartnerCandidate> =
            candidates.take(limit)

        override fun tryClaimEligiblePartnerForUpdate(
            anchorQueueEntryId: UUID,
            partnerQueueEntryId: UUID,
            today: LocalDate,
            exclusionPolicy: MatchmakingPairExclusionPolicy,
            previousPairingCutoff: OffsetDateTime?,
            firstChatExpirationCutoff: OffsetDateTime?
        ): MatchmakingPartnerCandidate? {
            claimAttempts.add(partnerQueueEntryId)
            return claimResults[partnerQueueEntryId]
        }
    }

    private fun anyUuidCollection(): Collection<UUID> =
        Mockito.anyCollection<UUID>().let { emptyList() }

    private fun captureUuidCollection(captor: ArgumentCaptor<Collection<UUID>>): Collection<UUID> {
        captor.capture()
        return emptyList()
    }

    private fun anyUuid(): UUID =
        Mockito.any(UUID::class.java).let { UUID.randomUUID() }

    private fun anyOffsetDateTime(): OffsetDateTime =
        Mockito.any(OffsetDateTime::class.java).let { OffsetDateTime.now() }
}
