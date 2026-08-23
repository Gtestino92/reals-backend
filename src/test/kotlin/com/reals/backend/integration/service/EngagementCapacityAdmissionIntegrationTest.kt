package com.reals.backend.integration.service

import com.reals.backend.domain.ActiveEngagementLock
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.matching.MatchmakingAvailabilityService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.UUID

@TestPropertySource(
    properties = [
        "user-reliability.enabled=true"
    ]
)
class EngagementCapacityAdmissionIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var matchmakingAvailabilityService: MatchmakingAvailabilityService

    @Test
    fun `availability uses dynamic match cap and hides numeric limit`() {
        val userId = activeFemale("dynamic-match-availability")
        recordNoShow(userId)
        saveLocks(
            userId = userId,
            type = EngagementType.MATCH,
            count = 4
        )

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId)

        assertFalse(availability.canSearch)
        assertEquals(DomainErrorCode.ACTIVE_MATCH_LIMIT_REACHED.name, availability.blockedReason?.code)
        assertEquals("User has reached the active match capacity", availability.blockedReason?.message)
        assertFalse(availability.blockedReason?.message.orEmpty().contains("(4)"))
    }

    @Test
    fun `availability uses dynamic connection cap and hides numeric limit`() {
        val userId = activeFemale("dynamic-connection-availability")
        recordNoShow(userId)
        saveLocks(
            userId = userId,
            type = EngagementType.CONNECTION,
            count = 3
        )

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId)

        assertFalse(availability.canSearch)
        assertEquals(DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED.name, availability.blockedReason?.code)
        assertEquals("User has reached the active connection capacity", availability.blockedReason?.message)
        assertFalse(availability.blockedReason?.message.orEmpty().contains("(3)"))
    }

    @Test
    fun `final match admission rejects when either participant reaches effective match cap`() {
        val cappedUserId = activeFemale("dynamic-final-match-cap")
        val partnerId = activeMale("dynamic-final-match-cap-partner")
        recordNoShow(cappedUserId)
        saveLocks(
            userId = cappedUserId,
            type = EngagementType.MATCH,
            count = 4
        )

        val exception = assertThrows<DomainConflictException> {
            matchService.createMatch(cappedUserId, partnerId)
        }

        assertEquals(DomainErrorCode.ACTIVE_MATCH_LIMIT_REACHED, exception.code)
        assertFalse(matchExistsForUsers(cappedUserId, partnerId))
        assertEquals(4, lockRepository.countByUserIdAndEngagementType(cappedUserId, EngagementType.MATCH))
    }

    @Test
    fun `final match admission rejects when either participant reaches effective connection cap`() {
        val cappedUserId = activeFemale("dynamic-final-connection-cap")
        val partnerId = activeMale("dynamic-final-connection-cap-partner")
        recordNoShow(cappedUserId)
        saveLocks(
            userId = cappedUserId,
            type = EngagementType.CONNECTION,
            count = 3
        )

        val exception = assertThrows<DomainConflictException> {
            matchService.createMatch(cappedUserId, partnerId)
        }

        assertEquals(DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED, exception.code)
        assertFalse(matchExistsForUsers(cappedUserId, partnerId))
        assertEquals(3, lockRepository.countByUserIdAndEngagementType(cappedUserId, EngagementType.CONNECTION))
    }

    @Test
    fun `admitted match can progress to connection after reliability cap falls`() {
        val setup = createMatchInVisualPhase()
        recordNoShow(setup.userAId)
        saveLocks(
            userId = setup.userAId,
            type = EngagementType.CONNECTION,
            count = 3
        )

        val before = matchmakingAvailabilityService.availabilityForUserNotInQueue(setup.userAId)
        assertFalse(before.canSearch)
        assertEquals(DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED.name, before.blockedReason?.code)

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        assertNotNull(connectionRepository.findByMatchId(setup.matchId))
        assertEquals(4, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertEquals(MatchState.VISUAL_APPROVED, matchRepository.findById(setup.matchId).orElseThrow().state)
    }

    @Test
    fun `existing active engagements are not removed when derived cap falls`() {
        val userId = activeFemale("dynamic-natural-overshoot")
        saveLocks(
            userId = userId,
            type = EngagementType.CONNECTION,
            count = 4
        )
        recordNoShow(userId)

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId)

        assertFalse(availability.canSearch)
        assertEquals(DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED.name, availability.blockedReason?.code)
        assertEquals(4, lockRepository.countByUserIdAndEngagementType(userId, EngagementType.CONNECTION))
    }

    @Test
    fun `visual advancement capacity remains independent from reliability`() {
        val userId = activeFemale("dynamic-visual-independent")
        recordNoShow(userId)

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId)

        assertTrue(availability.canSearch)
        assertNull(availability.blockedReason)
    }

    @Test
    fun `processor removes stale queue user capped by final connection admission`() {
        val cappedUserId = activeFemale("dynamic-processor-capped")
        val partnerId = activeMale("dynamic-processor-partner")
        enqueueForMatchmaking(cappedUserId)
        enqueueForMatchmaking(partnerId)
        recordNoShow(cappedUserId)
        saveLocks(
            userId = cappedUserId,
            type = EngagementType.CONNECTION,
            count = 3
        )

        val result = matchmakingProcessorService.process(maxPairsPerRun = 1)

        assertEquals(1, result.candidatePairs)
        assertEquals(0, result.matchesCreated)
        assertEquals(0, result.failedPairs)
        assertFalse(matchmakingQueueRepository.existsByUserId(cappedUserId))
        assertTrue(matchmakingQueueRepository.existsByUserId(partnerId))
        assertFalse(matchExistsForUsers(cappedUserId, partnerId))
    }

    private fun recordNoShow(userId: UUID) {
        userReliabilityScoreService.recordEvent(
            userId = userId,
            eventType = UserReliabilityEventType.SECOND_CHAT_NO_SHOW,
            relatedConnectionId = UUID.randomUUID()
        )
    }

    private fun saveLocks(
        userId: UUID,
        type: EngagementType,
        count: Int
    ) {
        repeat(count) {
            lockRepository.save(
                ActiveEngagementLock(
                    userId = userId,
                    engagementId = UUID.randomUUID(),
                    engagementType = type
                )
            )
        }
        lockRepository.flush()
    }

    private fun activeFemale(prefix: String): UUID =
        createActiveProfile(
            email = "$prefix-${UUID.randomUUID()}@example.com",
            displayName = "$prefix user",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

    private fun activeMale(prefix: String): UUID =
        createActiveProfile(
            email = "$prefix-${UUID.randomUUID()}@example.com",
            displayName = "$prefix user",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

}
