package com.reals.backend.integration.service

import com.reals.backend.domain.ActiveEngagementLock
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.VisualDecision
import com.reals.backend.domain.VisualReview
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.MeHomeService
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
import java.time.OffsetDateTime
import java.util.UUID

@TestPropertySource(
    properties = [
        "matchmaking.visual-advancement.max-per-window=10",
        "matchmaking.visual-advancement.window-hours=24"
    ]
)
class VisualAdvancementCapIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var matchmakingAvailabilityService: MatchmakingAvailabilityService

    @Autowired
    private lateinit var meHomeService: MeHomeService

    @Test
    fun `zero advancements can search`() {
        val now = fixedNow()
        val userId = activeFemale("zero")

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId, now)

        assertTrue(availability.canSearch)
        assertNull(availability.blockedReason)
    }

    @Test
    fun `nine active advancements can search`() {
        val now = fixedNow()
        val userId = activeFemale("nine")
        saveAdvancements(userId, count = 9, firstCreatedAt = now.minusHours(23))

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId, now)

        assertTrue(availability.canSearch)
        assertNull(availability.blockedReason)
    }

    @Test
    fun `ten active advancements block search with next available time`() {
        val now = fixedNow()
        val userId = activeFemale("ten")
        val oldest = now.minusHours(23).minusMinutes(40)
        saveAdvancements(userId, count = 10, firstCreatedAt = oldest)

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId, now)

        assertFalse(availability.canSearch)
        assertEquals(DomainErrorCode.VISUAL_ADVANCEMENT_LIMIT_REACHED.name, availability.blockedReason?.code)
        assertEquals(oldest.plusHours(24), availability.blockedReason?.nextAvailableAt)
    }

    @Test
    fun `twelve active advancements use tenth most recent advancement for next available time`() {
        val now = fixedNow()
        val userId = activeFemale("twelve")
        val oldest = now.minusHours(23).minusMinutes(40)
        saveAdvancements(userId, count = 12, firstCreatedAt = oldest)

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId, now)

        assertFalse(availability.canSearch)
        assertEquals(DomainErrorCode.VISUAL_ADVANCEMENT_LIMIT_REACHED.name, availability.blockedReason?.code)
        assertEquals(oldest.plusMinutes(2).plusHours(24), availability.blockedReason?.nextAvailableAt)
    }

    @Test
    fun `eleven historical advancements with nine in the active window can search`() {
        val now = fixedNow()
        val userId = activeFemale("historical")
        saveAdvancements(userId, count = 2, firstCreatedAt = now.minusHours(25))
        saveAdvancements(userId, count = 9, firstCreatedAt = now.minusHours(23))

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId, now)

        assertTrue(availability.canSearch)
        assertNull(availability.blockedReason)
    }

    @Test
    fun `oldest advancement at exact twenty four hour boundary no longer counts`() {
        val now = fixedNow()
        val blockedUserId = activeFemale("boundary-blocked")
        val boundaryUserId = activeFemale("boundary-allowed")

        saveVisualAdvancement(blockedUserId, createdAt = now.minusHours(24).plusSeconds(1))
        saveAdvancements(blockedUserId, count = 9, firstCreatedAt = now.minusHours(23))

        val blocked = matchmakingAvailabilityService.availabilityForUserNotInQueue(blockedUserId, now)
        assertFalse(blocked.canSearch)
        assertEquals(now.plusSeconds(1), blocked.blockedReason?.nextAvailableAt)

        saveVisualAdvancement(boundaryUserId, createdAt = now.minusHours(24))
        saveAdvancements(boundaryUserId, count = 9, firstCreatedAt = now.minusHours(23))

        val allowed = matchmakingAvailabilityService.availabilityForUserNotInQueue(boundaryUserId, now)
        assertTrue(allowed.canSearch)
        assertNull(allowed.blockedReason)
    }

    @Test
    fun `visual decisions and expiry do not alter cap calculation or next available time`() {
        val now = fixedNow()
        val userId = activeFemale("outcomes")
        val oldest = now.minusHours(23)
        val reviews = listOf(
            saveVisualAdvancement(
                userId = userId,
                createdAt = oldest,
                state = MatchState.VISUAL_REJECTED,
                userDecision = VisualDecision.REJECTED
            ),
            saveVisualAdvancement(
                userId = userId,
                createdAt = oldest.plusMinutes(1),
                state = MatchState.VISUAL_APPROVED,
                userDecision = VisualDecision.APPROVED,
                partnerDecision = VisualDecision.APPROVED
            ),
            saveVisualAdvancement(
                userId = userId,
                createdAt = oldest.plusMinutes(2),
                state = MatchState.EXPIRED
            )
        ) + saveAdvancements(userId, count = 7, firstCreatedAt = oldest.plusMinutes(3))

        val before = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId, now)

        assertFalse(before.canSearch)
        assertEquals(oldest.plusHours(24), before.blockedReason?.nextAvailableAt)

        reviews.forEachIndexed { index, review ->
            review.userAVisualDecision = if (index % 2 == 0) VisualDecision.APPROVED else VisualDecision.REJECTED
            review.userBVisualDecision = if (index % 2 == 0) VisualDecision.APPROVED else null
            review.expiresAt = now.minusMinutes(1)
        }
        visualReviewRepository.saveAllAndFlush(reviews)
        val matches = matchRepository.findAllById(reviews.map { it.matchId })
        matches.forEachIndexed { index, match ->
            match.state = when (index % 3) {
                0 -> MatchState.VISUAL_APPROVED
                1 -> MatchState.VISUAL_REJECTED
                else -> MatchState.EXPIRED
            }
            match.updatedAt = now.plusMinutes(index.toLong())
        }
        matchRepository.saveAllAndFlush(matches)

        val after = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId, now)

        assertFalse(after.canSearch)
        assertEquals(before.blockedReason?.nextAvailableAt, after.blockedReason?.nextAvailableAt)
    }

    @Test
    fun `mutual first chat approval can create tenth review and next enqueue is blocked`() {
        val now = OffsetDateTime.now().withNano(0)
        val userId = activeFemale("approval-tenth")
        val partnerId = activeMale("approval-tenth-partner")
        saveAdvancements(userId, count = 9, firstCreatedAt = now.minusHours(23))

        val match = matchService.createMatch(userId, partnerId)
        chatService.startFirstChat(match.id)

        chatService.recordChatDecision(match.id, userId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(match.id, partnerId, ChatContinueDecision.APPROVED)

        assertNotNull(visualReviewRepository.findByMatchId(match.id))
        assertEquals(
            DomainErrorCode.VISUAL_ADVANCEMENT_LIMIT_REACHED,
            assertThrows<DomainConflictException> {
                enqueueForMatchmaking(userId)
            }.code
        )
    }

    @Test
    fun `stale queued user capped by another engagement is removed and receives no new match`() {
        val now = OffsetDateTime.now().withNano(0)
        val userId = activeFemale("stale-queued")
        val existingPartnerId = activeMale("stale-existing")
        val queuedCandidateId = activeMale("stale-candidate")
        saveAdvancements(userId, count = 9, firstCreatedAt = now.minusHours(23))

        val existingMatch = matchService.createMatch(userId, existingPartnerId)
        chatService.startFirstChat(existingMatch.id)
        enqueueForMatchmaking(userId)
        enqueueForMatchmaking(queuedCandidateId)

        chatService.recordChatDecision(existingMatch.id, userId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(existingMatch.id, existingPartnerId, ChatContinueDecision.APPROVED)

        val result = matchmakingProcessorService.process(maxPairsPerRun = 1)

        assertEquals(0, result.matchesCreated)
        assertFalse(matchExistsForUsers(userId, queuedCandidateId))
        assertFalse(matchmakingQueueRepository.existsByUserId(userId))
        assertTrue(matchmakingQueueRepository.existsByUserId(queuedCandidateId))
    }

    @Test
    fun `cap is calculated independently per participant`() {
        val now = fixedNow()
        val userAId = activeFemale("independent-a")
        val userBId = activeMale("independent-b")
        saveAdvancements(userAId, count = 10, firstCreatedAt = now.minusHours(23))
        saveAdvancements(userBId, count = 9, firstCreatedAt = now.minusHours(23))

        val userAAvailability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userAId, now)
        val userBAvailability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userBId, now)

        assertFalse(userAAvailability.canSearch)
        assertTrue(userBAvailability.canSearch)
    }

    @Test
    fun `existing active match and connection caps keep their stable reasons`() {
        val now = fixedNow()
        val matchCappedUserId = activeFemale("active-match-cap")
        val connectionCappedUserId = activeFemale("active-connection-cap")

        repeat(5) {
            lockRepository.save(
                ActiveEngagementLock(
                    userId = matchCappedUserId,
                    engagementId = UUID.randomUUID(),
                    engagementType = EngagementType.MATCH
                )
            )
        }
        repeat(2) {
            lockRepository.save(
                ActiveEngagementLock(
                    userId = connectionCappedUserId,
                    engagementId = UUID.randomUUID(),
                    engagementType = EngagementType.CONNECTION
                )
            )
        }
        lockRepository.flush()

        val matchAvailability = matchmakingAvailabilityService.availabilityForUserNotInQueue(matchCappedUserId, now)
        val connectionAvailability = matchmakingAvailabilityService.availabilityForUserNotInQueue(connectionCappedUserId, now)

        assertEquals(DomainErrorCode.ACTIVE_MATCH_LIMIT_REACHED.name, matchAvailability.blockedReason?.code)
        assertEquals(DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED.name, connectionAvailability.blockedReason?.code)
        assertNull(matchAvailability.blockedReason?.nextAvailableAt)
        assertNull(connectionAvailability.blockedReason?.nextAvailableAt)
    }

    @Test
    fun `home exposes visual advancement retry timestamp and schedules refresh`() {
        val now = OffsetDateTime.now().withNano(0)
        val userId = activeFemale("home")
        val oldest = now.minusHours(23)
        saveAdvancements(userId, count = 10, firstCreatedAt = oldest)

        val projection = meHomeService.getHomeProjection(userId)

        assertFalse(projection.home.matchmaking.canSearch)
        assertEquals(
            DomainErrorCode.VISUAL_ADVANCEMENT_LIMIT_REACHED.name,
            projection.home.matchmaking.blockedReason?.code
        )
        assertEquals(oldest.plusHours(24), projection.home.matchmaking.blockedReason?.nextAvailableAt)
        assertEquals(oldest.plusHours(24), projection.nextRefreshAt)
    }

    private fun saveAdvancements(
        userId: UUID,
        count: Int,
        firstCreatedAt: OffsetDateTime
    ): List<VisualReview> =
        (0 until count).map { index ->
            saveVisualAdvancement(
                userId = userId,
                createdAt = firstCreatedAt.plusMinutes(index.toLong())
            )
        }

    private fun saveVisualAdvancement(
        userId: UUID,
        createdAt: OffsetDateTime,
        state: MatchState = MatchState.VISUAL_PHASE,
        userDecision: VisualDecision? = null,
        partnerDecision: VisualDecision? = null
    ): VisualReview {
        val match = matchRepository.saveAndFlush(
            Match(
                userAId = userId,
                userBId = UUID.randomUUID(),
                state = state,
                createdAt = createdAt.minusMinutes(15),
                updatedAt = createdAt
            )
        )
        return visualReviewRepository.saveAndFlush(
            VisualReview(
                matchId = match.id,
                userAVisualDecision = userDecision,
                userBVisualDecision = partnerDecision,
                expiresAt = createdAt.plusHours(24),
                availableAt = createdAt,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        )
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

    private fun fixedNow(): OffsetDateTime =
        OffsetDateTime.parse("2026-07-14T12:00:00Z")
}

@TestPropertySource(
    properties = [
        "matchmaking.visual-advancement.max-per-window=2",
        "matchmaking.visual-advancement.window-hours=24"
    ]
)
class VisualAdvancementCapConfigurationIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var matchmakingAvailabilityService: MatchmakingAvailabilityService

    @Test
    fun `max per window retry threshold is configurable`() {
        val now = OffsetDateTime.parse("2026-07-14T12:00:00Z")
        val userId = createActiveProfile(
            email = "configurable-${UUID.randomUUID()}@example.com",
            displayName = "Configurable Cap",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val oldest = now.minusHours(23)

        repeat(4) { index ->
            val createdAt = oldest.plusMinutes(index.toLong())
            val match = matchRepository.saveAndFlush(
                Match(
                    userAId = userId,
                    userBId = UUID.randomUUID(),
                    state = MatchState.VISUAL_PHASE,
                    createdAt = createdAt.minusMinutes(15),
                    updatedAt = createdAt
                )
            )
            visualReviewRepository.saveAndFlush(
                VisualReview(
                    matchId = match.id,
                    expiresAt = createdAt.plusHours(24),
                    availableAt = createdAt,
                    createdAt = createdAt,
                    updatedAt = createdAt
                )
            )
        }

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId, now)

        assertFalse(availability.canSearch)
        assertEquals(DomainErrorCode.VISUAL_ADVANCEMENT_LIMIT_REACHED.name, availability.blockedReason?.code)
        assertEquals(oldest.plusMinutes(2).plusHours(24), availability.blockedReason?.nextAvailableAt)
    }
}
