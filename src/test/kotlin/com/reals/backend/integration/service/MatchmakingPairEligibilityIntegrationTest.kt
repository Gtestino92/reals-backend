package com.reals.backend.integration.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.domain.VisualReview
import com.reals.backend.integration.BaseIT
import com.reals.backend.repository.matching.MatchmakingPairBlockingReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

class MatchmakingPairEligibilityIntegrationTest : BaseIT() {

    @Test
    fun `no previous interaction leaves compatible queued pair eligible`() {
        val now = fixedNow()
        val (userA, userB) = createQueuedCompatiblePair("eligible-new")

        assertTrue(matchmakingPairEligibilityService.isPairEligible(userA, userB, now))
        assertTrue(queryContainsPair(userA, userB, now))
        assertEquals(null, blockingReason(userA, userB, now))
    }

    @Test
    fun `active matches and approved transition without connection are excluded`() {
        val now = fixedNow()
        val chatActive = createQueuedCompatiblePair("active-chat")
        saveHistoricalMatch(chatActive.first, chatActive.second, MatchState.CHAT_ACTIVE, now)
        val visualPhase = createQueuedCompatiblePair("active-visual")
        saveHistoricalMatch(visualPhase.first, visualPhase.second, MatchState.VISUAL_PHASE, now)
        val approvedWithoutConnection = createQueuedCompatiblePair("approved-no-connection")
        saveHistoricalMatch(approvedWithoutConnection.first, approvedWithoutConnection.second, MatchState.VISUAL_APPROVED, now)

        listOf(chatActive, visualPhase, approvedWithoutConnection).forEach { (userA, userB) ->
            assertFalse(matchmakingPairEligibilityService.isPairEligible(userA, userB, now))
            assertFalse(queryContainsPair(userA, userB, now))
            assertEquals(MatchmakingPairBlockingReason.ACTIVE_INTERACTION, blockingReason(userA, userB, now))
        }
    }

    @Test
    fun `active connection excludes pair but does not exclude either user with another candidate`() {
        val now = fixedNow()
        val (userA, userB) = createQueuedCompatiblePair("active-connection")
        val userC = createActiveProfile(
            email = "active-connection-c-${UUID.randomUUID()}@example.com",
            displayName = "Active Connection C",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        enqueueForMatchmaking(userC)
        saveConnection(userA, userB, ConnectionState.SCHEDULING_PHASE, now)

        assertFalse(matchmakingPairEligibilityService.isPairEligible(userA, userB, now))
        assertFalse(queryContainsPair(userA, userB, now))
        assertEquals(MatchmakingPairBlockingReason.ACTIVE_INTERACTION, blockingReason(userB, userA, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(userA, userC, now))
        assertTrue(queryContainsPair(userA, userC, now))
    }

    @Test
    fun `defensive blocking reason prioritizes active interaction over historical cooldown`() {
        val now = fixedNow()
        val (userA, userB) = createQueuedCompatiblePair("blocking-priority")
        saveHistoricalMatch(userA, userB, MatchState.CHAT_REJECTED, now.minusDays(1))
        saveHistoricalMatch(userA, userB, MatchState.CHAT_ACTIVE, now)

        assertEquals(MatchmakingPairBlockingReason.ACTIVE_INTERACTION, blockingReason(userA, userB, now))
    }

    @Test
    fun `match creation refuses active duplicate after users are locked`() {
        val userA = createActiveProfile(
            email = "duplicate-active-a-${UUID.randomUUID()}@example.com",
            displayName = "Duplicate Active A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "duplicate-active-b-${UUID.randomUUID()}@example.com",
            displayName = "Duplicate Active B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        matchService.createMatch(userA, userB)

        assertThrows<IllegalStateException> {
            matchService.createMatch(userA, userB)
        }
        assertEquals(
            1,
            matchRepository.findAll().count {
                (it.userAId == userA && it.userBId == userB) ||
                    (it.userAId == userB && it.userBId == userA)
            }
        )
    }

    @Test
    fun `match creation refuses recent historical cooldown after users are locked`() {
        val now = fixedNow()
        val userA = createActiveProfile(
            email = "duplicate-history-a-${UUID.randomUUID()}@example.com",
            displayName = "Duplicate History A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "duplicate-history-b-${UUID.randomUUID()}@example.com",
            displayName = "Duplicate History B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        saveHistoricalMatch(userA, userB, MatchState.CHAT_REJECTED, now.minusDays(1))

        assertThrows<IllegalStateException> {
            matchService.createMatch(userA, userB)
        }
        assertEquals(
            1,
            matchRepository.findAll().count {
                (it.userAId == userA && it.userBId == userB) ||
                    (it.userAId == userB && it.userBId == userA)
            }
        )
    }

    @Test
    fun `thirty day terminal outcomes use strict cooldown boundary`() {
        assertThirtyDayBoundary("chat-rejected", MatchState.CHAT_REJECTED)
        assertThirtyDayBoundary("visual-rejected", MatchState.VISUAL_REJECTED)
        assertVisualExpirationBoundary()
        assertClosedConnectionBoundary()
    }

    @Test
    fun `first chat automatic terminal outcomes use seven day strict boundary`() {
        assertFirstChatBoundary(
            prefix = "first-chat-expired",
            status = ChatStatus.EXPIRED,
            endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
        )
        assertFirstChatBoundary(
            prefix = "first-chat-abandoned",
            status = ChatStatus.ABANDONED,
            endedReason = ChatEndReason.INACTIVITY_TIMEOUT
        )
    }

    @Test
    fun `expired match classification uses visual review evidence and chat fallback timestamps`() {
        val now = fixedNow()
        val firstChatLegacy = createQueuedCompatiblePair("expired-legacy-first-chat")
        saveExpiredFirstChatMatch(
            userAId = firstChatLegacy.first,
            userBId = firstChatLegacy.second,
            updatedAt = now.minusDays(6),
            endedAt = null
        )
        val visualExpired = createQueuedCompatiblePair("expired-visual-review")
        saveExpiredVisualReviewMatch(
            userAId = visualExpired.first,
            userBId = visualExpired.second,
            updatedAt = now.minusDays(8)
        )

        assertFalse(matchmakingPairEligibilityService.isPairEligible(firstChatLegacy.first, firstChatLegacy.second, now))
        assertFalse(queryContainsPair(firstChatLegacy.first, firstChatLegacy.second, now))
        assertFalse(matchmakingPairEligibilityService.isPairEligible(visualExpired.first, visualExpired.second, now))
        assertFalse(queryContainsPair(visualExpired.first, visualExpired.second, now))
    }

    @Test
    fun `multiple histories exclude only when at least one cooldown remains active`() {
        val now = fixedNow()
        val recentAfterOld = createQueuedCompatiblePair("multiple-recent")
        saveHistoricalMatch(recentAfterOld.first, recentAfterOld.second, MatchState.CHAT_REJECTED, now.minusDays(60))
        saveHistoricalMatch(recentAfterOld.first, recentAfterOld.second, MatchState.VISUAL_REJECTED, now.minusDays(3))
        val allExpired = createQueuedCompatiblePair("multiple-expired")
        saveHistoricalMatch(allExpired.first, allExpired.second, MatchState.CHAT_REJECTED, now.minusDays(31))
        saveConnection(allExpired.first, allExpired.second, ConnectionState.CLOSED, now.minusDays(30))
        val otherCandidate = createActiveProfile(
            email = "multiple-other-c-${UUID.randomUUID()}@example.com",
            displayName = "Multiple Other C",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        enqueueForMatchmaking(otherCandidate)

        assertFalse(matchmakingPairEligibilityService.isPairEligible(recentAfterOld.first, recentAfterOld.second, now))
        assertFalse(queryContainsPair(recentAfterOld.first, recentAfterOld.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(allExpired.first, allExpired.second, now))
        assertTrue(queryContainsPair(allExpired.first, allExpired.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(recentAfterOld.first, otherCandidate, now))
    }

    @Test
    fun `blocks remain permanent and normal rejection does not create a block`() {
        val now = fixedNow()
        val blocked = createQueuedCompatiblePair("blocked-permanent")
        userBlockService.blockUser(
            blockerUserId = blocked.first,
            blockedUserId = blocked.second,
            source = UserBlockSource.MANUAL
        )
        val rejected = createQueuedCompatiblePair("normal-rejection")
        saveHistoricalMatch(rejected.first, rejected.second, MatchState.CHAT_REJECTED, now.minusDays(1))

        assertFalse(matchmakingPairEligibilityService.isPairEligible(blocked.first, blocked.second, now))
        assertFalse(queryContainsPair(blocked.first, blocked.second, now))
        assertFalse(userBlockRepository.existsBetweenUsers(rejected.first, rejected.second))
    }

    private fun assertThirtyDayBoundary(
        prefix: String,
        state: MatchState
    ) {
        val now = fixedNow()
        val recent = createQueuedCompatiblePair("$prefix-recent")
        saveHistoricalMatch(recent.first, recent.second, state, now.minusDays(29))
        val boundary = createQueuedCompatiblePair("$prefix-boundary")
        saveHistoricalMatch(boundary.first, boundary.second, state, now.minusDays(30))

        assertFalse(matchmakingPairEligibilityService.isPairEligible(recent.first, recent.second, now))
        assertFalse(queryContainsPair(recent.first, recent.second, now))
        assertEquals(MatchmakingPairBlockingReason.PREVIOUS_PAIRING_COOLDOWN, blockingReason(recent.first, recent.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(boundary.first, boundary.second, now))
        assertTrue(queryContainsPair(boundary.first, boundary.second, now))
        assertEquals(null, blockingReason(boundary.first, boundary.second, now))
    }

    private fun assertVisualExpirationBoundary() {
        val now = fixedNow()
        val recent = createQueuedCompatiblePair("visual-expired-recent")
        saveExpiredVisualReviewMatch(recent.first, recent.second, now.minusDays(29))
        val boundary = createQueuedCompatiblePair("visual-expired-boundary")
        saveExpiredVisualReviewMatch(boundary.first, boundary.second, now.minusDays(30))

        assertFalse(matchmakingPairEligibilityService.isPairEligible(recent.first, recent.second, now))
        assertFalse(queryContainsPair(recent.first, recent.second, now))
        assertEquals(MatchmakingPairBlockingReason.PREVIOUS_PAIRING_COOLDOWN, blockingReason(recent.first, recent.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(boundary.first, boundary.second, now))
        assertTrue(queryContainsPair(boundary.first, boundary.second, now))
        assertEquals(null, blockingReason(boundary.first, boundary.second, now))
    }

    private fun assertClosedConnectionBoundary() {
        val now = fixedNow()
        val recent = createQueuedCompatiblePair("closed-connection-recent")
        saveConnection(recent.first, recent.second, ConnectionState.CLOSED, now.minusDays(29))
        val boundary = createQueuedCompatiblePair("closed-connection-boundary")
        saveConnection(boundary.first, boundary.second, ConnectionState.CLOSED, now.minusDays(30))

        assertFalse(matchmakingPairEligibilityService.isPairEligible(recent.first, recent.second, now))
        assertFalse(queryContainsPair(recent.first, recent.second, now))
        assertEquals(MatchmakingPairBlockingReason.PREVIOUS_PAIRING_COOLDOWN, blockingReason(recent.first, recent.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(boundary.first, boundary.second, now))
        assertTrue(queryContainsPair(boundary.first, boundary.second, now))
        assertEquals(null, blockingReason(boundary.first, boundary.second, now))
    }

    private fun assertFirstChatBoundary(
        prefix: String,
        status: ChatStatus,
        endedReason: ChatEndReason
    ) {
        val now = fixedNow()
        val recent = createQueuedCompatiblePair("$prefix-recent")
        saveExpiredFirstChatMatch(
            userAId = recent.first,
            userBId = recent.second,
            updatedAt = now.minusDays(20),
            endedAt = now.minusDays(6),
            status = status,
            endedReason = endedReason
        )
        val boundary = createQueuedCompatiblePair("$prefix-boundary")
        saveExpiredFirstChatMatch(
            userAId = boundary.first,
            userBId = boundary.second,
            updatedAt = now.minusDays(20),
            endedAt = now.minusDays(7),
            status = status,
            endedReason = endedReason
        )

        assertFalse(matchmakingPairEligibilityService.isPairEligible(recent.first, recent.second, now))
        assertFalse(queryContainsPair(recent.first, recent.second, now))
        assertEquals(MatchmakingPairBlockingReason.PREVIOUS_PAIRING_COOLDOWN, blockingReason(recent.first, recent.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(boundary.first, boundary.second, now))
        assertTrue(queryContainsPair(boundary.first, boundary.second, now))
        assertEquals(null, blockingReason(boundary.first, boundary.second, now))
    }

    private fun createQueuedCompatiblePair(prefix: String): Pair<UUID, UUID> {
        val userA = createActiveProfile(
            email = "$prefix-a-${UUID.randomUUID()}@example.com",
            displayName = "$prefix A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "$prefix-b-${UUID.randomUUID()}@example.com",
            displayName = "$prefix B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)
        return Pair(userA, userB)
    }

    private fun fixedNow(): OffsetDateTime =
        OffsetDateTime.parse("2026-07-14T12:00:00Z")

    private fun queryContainsPair(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime
    ): Boolean {
        val previousPairingCutoff = matchmakingPairEligibilityService.previousPairingCutoff(now)
        val firstChatExpirationCutoff = matchmakingPairEligibilityService.firstChatExpirationCutoff(now)
        val userAQueueEntry = matchmakingQueueRepository.findByUserId(userAId)
        val userBQueueEntry = matchmakingQueueRepository.findByUserId(userBId)

        return listOfNotNull(userAQueueEntry, userBQueueEntry).any { anchor ->
            matchmakingCandidateRepository.findEligiblePartnerCandidates(
                anchorQueueEntryId = anchor.id,
                limit = 100,
                today = now.toLocalDate(),
                previousPairingCutoff = previousPairingCutoff,
                firstChatExpirationCutoff = firstChatExpirationCutoff
            ).any {
                (it.pair.userAId == userAId && it.pair.userBId == userBId) ||
                    (it.pair.userAId == userBId && it.pair.userBId == userAId)
            }
        }
    }

    private fun blockingReason(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime
    ): MatchmakingPairBlockingReason? =
        matchmakingPairEligibilityRepository.findBlockingReason(
            userAId = userAId,
            userBId = userBId,
            previousPairingCutoff = matchmakingPairEligibilityService.previousPairingCutoff(now),
            firstChatExpirationCutoff = matchmakingPairEligibilityService.firstChatExpirationCutoff(now)
        )

    private fun saveHistoricalMatch(
        userAId: UUID,
        userBId: UUID,
        state: MatchState,
        updatedAt: OffsetDateTime
    ): Match =
        matchRepository.saveAndFlush(
            Match(
                userAId = userAId,
                userBId = userBId,
                state = state,
                createdAt = updatedAt.minusMinutes(1),
                updatedAt = updatedAt
            )
        )

    private fun saveExpiredFirstChatMatch(
        userAId: UUID,
        userBId: UUID,
        updatedAt: OffsetDateTime,
        endedAt: OffsetDateTime?,
        status: ChatStatus = ChatStatus.EXPIRED,
        endedReason: ChatEndReason = ChatEndReason.ABSOLUTE_TIMEOUT
    ): Match {
        val match = saveHistoricalMatch(userAId, userBId, MatchState.EXPIRED, updatedAt)
        chatRepository.saveAndFlush(
            Chat(
                matchId = match.id,
                chatType = ChatType.FIRST_CHAT,
                status = status,
                startedAt = updatedAt.minusMinutes(16),
                timeoutAt = updatedAt.minusMinutes(1),
                endedAt = endedAt,
                endedReason = endedReason
            )
        )
        return match
    }

    private fun saveExpiredVisualReviewMatch(
        userAId: UUID,
        userBId: UUID,
        updatedAt: OffsetDateTime
    ): Match {
        val match = saveHistoricalMatch(userAId, userBId, MatchState.EXPIRED, updatedAt)
        visualReviewRepository.saveAndFlush(
            VisualReview(
                matchId = match.id,
                expiresAt = updatedAt.minusSeconds(1),
                createdAt = updatedAt.minusDays(1),
                updatedAt = updatedAt
            )
        )
        return match
    }

    private fun saveConnection(
        userAId: UUID,
        userBId: UUID,
        state: ConnectionState,
        updatedAt: OffsetDateTime
    ): Connection {
        val match = saveHistoricalMatch(userAId, userBId, MatchState.VISUAL_APPROVED, updatedAt.minusDays(60))
        return connectionRepository.saveAndFlush(
            Connection(
                matchId = match.id,
                userAId = userAId,
                userBId = userBId,
                state = state,
                schedulingExpiresAt = updatedAt.plusDays(1),
                createdAt = updatedAt.minusMinutes(1),
                updatedAt = updatedAt
            )
        )
    }
}
