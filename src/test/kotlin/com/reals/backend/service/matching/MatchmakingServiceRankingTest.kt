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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `probabilistic raw compatibility gate discards candidate before claim`() {
        val candidate = candidate("below-minimum", partnerAUserId)
        val candidateRepository =
            FakeCandidateRepository(
                candidates = listOf(candidate)
            )
        candidateRepository.claimResults[candidate.partnerQueueEntryId] = candidate
        val reliabilityService = reliabilityService(enabled = true)
        Mockito.`when`(
            reliabilityService.effectiveScores(anyUuidCollection(), anyOffsetDateTime())
        ).thenReturn(
            mapOf(
                anchorUserId to 100.0,
                partnerAUserId to 100.0
            )
        )

        val pair =
            service(
                mode = MatchmakingRankingMode.PROBABILISTIC_WEIGHTED,
                candidateRepository = candidateRepository,
                reliabilityService = reliabilityService,
                randomValues = listOf(0.99),
                compatibilityScore = 0.19,
                minCompatibilityScore = 0.2
            ).claimNextCandidatePair()

        assertNull(pair)
        assertEquals(emptyList<UUID>(), candidateRepository.claimAttempts)
    }

    @Test
    fun `probabilistic and legacy modes apply minimum score to different scores`() {
        val candidate = candidate("minimum-semantics", partnerAUserId)
        val probabilisticRepository =
            FakeCandidateRepository(
                candidates = listOf(candidate)
            )
        probabilisticRepository.claimResults[candidate.partnerQueueEntryId] = candidate
        val probabilisticReliability = reliabilityService(enabled = true)
        Mockito.`when`(
            probabilisticReliability.effectiveScores(anyUuidCollection(), anyOffsetDateTime())
        ).thenReturn(
            mapOf(
                anchorUserId to 100.0,
                partnerAUserId to 120.0
            )
        )

        val probabilisticPair =
            service(
                mode = MatchmakingRankingMode.PROBABILISTIC_WEIGHTED,
                candidateRepository = probabilisticRepository,
                reliabilityService = probabilisticReliability,
                randomValues = listOf(0.99),
                compatibilityScore = 0.15,
                minCompatibilityScore = 0.2
            ).claimNextCandidatePair()

        assertNull(probabilisticPair)
        assertEquals(emptyList<UUID>(), probabilisticRepository.claimAttempts)

        val legacyRepository =
            FakeCandidateRepository(
                candidates = listOf(candidate)
            )
        legacyRepository.claimResults[candidate.partnerQueueEntryId] = candidate
        val legacyReliability = reliabilityService(enabled = true)
        Mockito.`when`(
            legacyReliability.effectiveScores(anyUuidCollection(), anyOffsetDateTime())
        ).thenReturn(
            mapOf(
                anchorUserId to 100.0,
                partnerAUserId to 120.0
            )
        )
        Mockito.`when`(legacyReliability.matchmakingModifierForScores(100.0, 120.0)).thenReturn(0.10)

        val legacyPair =
            service(
                mode = MatchmakingRankingMode.LEGACY_EARLY_ACCEPT,
                candidateRepository = legacyRepository,
                reliabilityService = legacyReliability,
                randomValues = emptyList(),
                compatibilityScore = 0.15,
                minCompatibilityScore = 0.2,
                earlyAcceptCompatibilityScore = 0.9
            ).claimNextCandidatePair()

        assertEquals(Pair(anchorUserId, partnerAUserId), legacyPair)
        assertEquals(listOf(candidate.partnerQueueEntryId), legacyRepository.claimAttempts)
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

    @Test
    fun `legacy fallback keeps fifo order when combined scores tie`() {
        val first = candidate("fifo-first", partnerAUserId)
        val second = candidate("fifo-second", partnerBUserId)
        val candidateRepository =
            FakeCandidateRepository(
                candidates = listOf(first, second)
            )
        candidateRepository.claimResults[first.partnerQueueEntryId] = first
        val reliabilityService = reliabilityService(enabled = true)
        Mockito.`when`(
            reliabilityService.effectiveScores(anyUuidCollection(), anyOffsetDateTime())
        ).thenReturn(
            mapOf(
                anchorUserId to 100.0,
                partnerAUserId to 100.0,
                partnerBUserId to 100.0
            )
        )
        Mockito.`when`(reliabilityService.matchmakingModifierForScores(100.0, 100.0)).thenReturn(0.0)

        val pair =
            service(
                mode = MatchmakingRankingMode.LEGACY_EARLY_ACCEPT,
                candidateRepository = candidateRepository,
                reliabilityService = reliabilityService,
                randomValues = emptyList(),
                compatibilityScore = 0.5,
                earlyAcceptCompatibilityScore = 0.9
            ).claimNextCandidatePair()

        assertEquals(Pair(anchorUserId, partnerAUserId), pair)
        assertEquals(listOf(first.partnerQueueEntryId), candidateRepository.claimAttempts)
    }

    @Test
    fun `probabilistic mode ignores invalid legacy-only early accept value`() {
        val candidate = candidate("probabilistic-legacy-threshold", partnerAUserId)
        val candidateRepository =
            FakeCandidateRepository(
                candidates = listOf(candidate)
            )
        candidateRepository.claimResults[candidate.partnerQueueEntryId] = candidate
        val reliabilityService = reliabilityService(enabled = false)

        val pair =
            service(
                mode = MatchmakingRankingMode.PROBABILISTIC_WEIGHTED,
                candidateRepository = candidateRepository,
                reliabilityService = reliabilityService,
                randomValues = listOf(0.5),
                earlyAcceptCompatibilityScore = 0.1,
                minCompatibilityScore = 0.2
            ).claimNextCandidatePair()

        assertEquals(Pair(anchorUserId, partnerAUserId), pair)
    }

    @Test
    fun `legacy mode still rejects invalid early accept value`() {
        val candidateRepository =
            FakeCandidateRepository(
                candidates = listOf(candidate("legacy-invalid-threshold", partnerAUserId))
            )

        assertThrows<IllegalArgumentException> {
            service(
                mode = MatchmakingRankingMode.LEGACY_EARLY_ACCEPT,
                candidateRepository = candidateRepository,
                reliabilityService = reliabilityService(enabled = false),
                randomValues = emptyList(),
                earlyAcceptCompatibilityScore = 0.1,
                minCompatibilityScore = 0.2
            ).claimNextCandidatePair()
        }
    }

    @Test
    fun `probabilistic ranking uses shared ranking time for reliability and waiting calculation`() {
        val candidate = candidate("shared-time", partnerAUserId)
        val candidateRepository =
            FakeCandidateRepository(
                candidates = listOf(candidate)
            )
        candidateRepository.claimResults[candidate.partnerQueueEntryId] = candidate
        val reliabilityService = reliabilityService(enabled = true)
        Mockito.`when`(
            reliabilityService.effectiveScores(anyUuidCollection(), anyOffsetDateTime())
        ).thenReturn(
            mapOf(
                anchorUserId to 100.0,
                partnerAUserId to 100.0
            )
        )
        val rankingProperties =
            MatchmakingRankingProperties(
                mode = MatchmakingRankingMode.PROBABILISTIC_WEIGHTED,
                compatibilityTemperature = 0.20,
                reliabilitySimilarityScale = 10.0,
                waitingRelaxationPeriodHours = 72.0,
                maximumSimilarityScaleMultiplier = 3.0
            )
        val weightPolicy = Mockito.mock(ProbabilisticMatchmakingWeightPolicy::class.java)
        Mockito.`when`(weightPolicy.calculate(anyWeightInput()))
            .thenReturn(
                MatchmakingCandidateWeight(
                    compatibilityLogWeight = 0.0,
                    reliabilityLogWeight = 0.0,
                    waitingHours = 0.0,
                    waitingMultiplier = 1.0,
                    effectiveReliabilitySimilarityScale = 10.0,
                    logWeight = 0.0
                )
            )

        service(
            mode = MatchmakingRankingMode.PROBABILISTIC_WEIGHTED,
            candidateRepository = candidateRepository,
            reliabilityService = reliabilityService,
            randomValues = listOf(0.5),
            rankingProperties = rankingProperties,
            probabilisticWeightPolicy = weightPolicy
        ).claimNextCandidatePair()

        @Suppress("UNCHECKED_CAST")
        val userIdsCaptor =
            ArgumentCaptor.forClass(Collection::class.java) as ArgumentCaptor<Collection<UUID>>
        val reliabilityNowCaptor = ArgumentCaptor.forClass(OffsetDateTime::class.java)
        Mockito.verify(reliabilityService).effectiveScores(
            captureUuidCollection(userIdsCaptor),
            captureOffsetDateTime(reliabilityNowCaptor)
        )
        val weightInputCaptor = ArgumentCaptor.forClass(MatchmakingCandidateWeightInput::class.java)
        Mockito.verify(weightPolicy).calculate(captureWeightInput(weightInputCaptor))

        assertEquals(reliabilityNowCaptor.value, weightInputCaptor.value.now)
        assertEquals(candidate.partnerEnteredAt, weightInputCaptor.value.partnerEnteredAt)
    }

    @Test
    fun `large probabilistic candidate window ranks complete window and batches reliability`() {
        val candidates =
            (1..520).map { index ->
                candidate(
                    label = "large-$index",
                    partnerUserId = UUID.nameUUIDFromBytes("large-partner-$index".toByteArray())
                )
            }
        val candidateRepository =
            FakeCandidateRepository(
                candidates = candidates
            )
        val window = candidates.take(500)
        candidateRepository.claimResults[window.last().partnerQueueEntryId] = window.last()
        val reliabilityService = reliabilityService(enabled = true)
        Mockito.`when`(
            reliabilityService.effectiveScores(anyUuidCollection(), anyOffsetDateTime())
        ).thenReturn(
            (listOf(anchorUserId) + window.map { it.pair.userBId })
                .associateWith { 100.0 }
        )
        val compatibilityScorer = CountingCompatibilityScorer(1.0)

        val pair =
            service(
                mode = MatchmakingRankingMode.PROBABILISTIC_WEIGHTED,
                candidateRepository = candidateRepository,
                reliabilityService = reliabilityService,
                randomValues = List(500) { 0.5 },
                candidatePairLimit = 500,
                compatibilityScorer = compatibilityScorer
            ).claimNextCandidatePair()

        @Suppress("UNCHECKED_CAST")
        val userIdsCaptor =
            ArgumentCaptor.forClass(Collection::class.java) as ArgumentCaptor<Collection<UUID>>
        Mockito.verify(reliabilityService, Mockito.times(1))
            .effectiveScores(captureUuidCollection(userIdsCaptor), anyOffsetDateTime())
        assertEquals((listOf(anchorUserId) + window.map { it.pair.userBId }).toSet(), userIdsCaptor.value.toSet())
        Mockito.verify(reliabilityService, Mockito.never()).matchmakingModifierForPair(
            anyUuid(),
            anyUuid(),
            anyOffsetDateTime()
        )
        assertEquals(500, compatibilityScorer.calls)
        assertEquals(window.map { it.partnerQueueEntryId }, candidateRepository.claimAttempts)
        assertEquals(Pair(anchorUserId, window.last().pair.userBId), pair)
    }

    private fun service(
        mode: MatchmakingRankingMode,
        candidateRepository: FakeCandidateRepository,
        reliabilityService: UserReliabilityScoreService,
        randomValues: List<Double>,
        compatibilityScore: Double = 1.0,
        earlyAcceptCompatibilityScore: Double = 0.9,
        minCompatibilityScore: Double = 0.2,
        candidatePairLimit: Int = 10,
        compatibilityScorer: CompatibilityScorer? = null,
        rankingProperties: MatchmakingRankingProperties? = null,
        probabilisticWeightPolicy: ProbabilisticMatchmakingWeightPolicy? = null
    ): MatchmakingService {
        val profileService = Mockito.mock(ProfileService::class.java)
        Mockito.`when`(profileService.findByUserIds(anyUuidCollection()))
            .thenReturn(
                (listOf(anchorUserId) + candidateRepository.candidates.map { it.pair.userBId })
                    .distinct()
                    .map { userId ->
                        if (userId == anchorUserId) {
                            profile(userId, Gender.FEMALE, Gender.MALE)
                        } else {
                            profile(userId, Gender.MALE, Gender.FEMALE)
                        }
                    }
            )
        val pairEligibilityService = Mockito.mock(MatchmakingPairEligibilityService::class.java)
        Mockito.`when`(pairEligibilityService.effectiveExclusionPolicy())
            .thenReturn(MatchmakingPairExclusionPolicy.ACTIVE_ONLY)
        val resolvedCompatibilityScorer = compatibilityScorer ?:
            object : CompatibilityScorer {
                override fun score(
                    profileA: Profile,
                    profileB: Profile
                ): Double =
                    compatibilityScore
            }
        val resolvedRankingProperties = rankingProperties ?:
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
            compatibilityScorer = resolvedCompatibilityScorer,
            searchLocationMatchFilter = SearchLocationMatchFilter(),
            homeStateInvalidationService = Mockito.mock(HomeStateInvalidationService::class.java),
            userReliabilityScoreService = reliabilityService,
            matchmakingPairEligibilityService = pairEligibilityService,
            rankingProperties = resolvedRankingProperties,
            probabilisticWeightPolicy = probabilisticWeightPolicy ?: ProbabilisticMatchmakingWeightPolicy(resolvedRankingProperties),
            weightedCandidateOrderer = WeightedMatchmakingCandidateOrderer(sequenceRandom(randomValues)),
            candidatePairLimit = candidatePairLimit,
            minCompatibilityScore = minCompatibilityScore,
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

    private fun captureOffsetDateTime(captor: ArgumentCaptor<OffsetDateTime>): OffsetDateTime {
        captor.capture()
        return OffsetDateTime.now()
    }

    private fun anyWeightInput(): MatchmakingCandidateWeightInput {
        Mockito.any(MatchmakingCandidateWeightInput::class.java)
        return MatchmakingCandidateWeightInput(
            compatibilityScore = 1.0,
            anchorReliabilityScore = 100.0,
            partnerReliabilityScore = 100.0,
            partnerEnteredAt = OffsetDateTime.now(),
            now = OffsetDateTime.now()
        )
    }

    private fun captureWeightInput(
        captor: ArgumentCaptor<MatchmakingCandidateWeightInput>
    ): MatchmakingCandidateWeightInput {
        captor.capture()
        return MatchmakingCandidateWeightInput(
            compatibilityScore = 1.0,
            anchorReliabilityScore = 100.0,
            partnerReliabilityScore = 100.0,
            partnerEnteredAt = OffsetDateTime.now(),
            now = OffsetDateTime.now()
        )
    }

    private fun anyUuid(): UUID =
        Mockito.any(UUID::class.java).let { UUID.randomUUID() }

    private fun anyOffsetDateTime(): OffsetDateTime =
        Mockito.any(OffsetDateTime::class.java).let { OffsetDateTime.now() }

    private class CountingCompatibilityScorer(
        private val score: Double
    ) : CompatibilityScorer {
        var calls: Int = 0
            private set

        override fun score(
            profileA: Profile,
            profileB: Profile
        ): Double {
            calls += 1
            return score
        }
    }
}
