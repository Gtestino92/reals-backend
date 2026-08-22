package com.reals.backend.integration.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.VisualResourceAccessPolicy
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import java.time.OffsetDateTime
import java.util.UUID

class VisualReviewIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var matchmakingAvailabilityService: MatchmakingAvailabilityService

    @Test
    fun `visual review initialization persists availableAt and expires from availability`() {
        val setup = createMatchInDelayedVisualPhase()
        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)

        assertTrue(review.availableAt.isAfter(review.createdAt))
        assertEquals(
            review.availableAt.plusMinutes(1440).toInstant(),
            review.expiresAt?.toInstant()
        )
        assertTrue(review.reminderEligibleAt?.isAfter(review.availableAt) == true)
    }

    @Test
    fun `visual review initialization retry returns existing review without resampling availability`() {
        val setup = createMatchInDelayedVisualPhase()
        val original = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        val originalAvailableAt = original.availableAt
        val originalExpiresAt = original.expiresAt

        val replayed = visualReviewService.initializeForMatch(
            matchId = setup.matchId,
            preResolutionPairReliabilityScore = 120.0
        )

        assertEquals(original.id, replayed.id)
        assertEquals(originalAvailableAt.toInstant(), replayed.availableAt.toInstant())
        assertEquals(originalExpiresAt?.toInstant(), replayed.expiresAt?.toInstant())
    }

    @Test
    fun `visual content access is denied before visual review availability`() {
        val setup = createMatchInDelayedVisualPhase()

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.requireVisualContentAccess(setup.matchId, setup.userAId)
        }

        assertEquals(DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE, exception.code)
    }

    @Test
    fun `visual decision is denied before visual review availability`() {
        val setup = createMatchInDelayedVisualPhase()

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        }

        assertEquals(DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE, exception.code)
        assertNull(visualReviewService.findByMatchIdOrThrow(setup.matchId).userAVisualDecision)
    }

    @Test
    fun `personal message is denied before visual review availability`() {
        val setup = createMatchInDelayedVisualPhase()

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Mensaje oculto")
        }

        assertEquals(DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE, exception.code)
        assertNull(visualReviewService.findByMatchIdOrThrow(setup.matchId).personalMessageA)
    }

    @Test
    fun `visual content access is allowed at exact availability boundary`() {
        val setup = createMatchInDelayedVisualPhase()
        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)

        val access = visualResourceAccessPolicyForTest().requireCanAccess(
            matchId = setup.matchId,
            requestingUserId = setup.userAId,
            now = review.availableAt
        )

        assertEquals(setup.matchId, access.match.id)
    }

    @Test
    fun `local dev availability override makes pending review available now and preserves creation time`() {
        val setup = createMatchInDelayedVisualPhase()
        val before = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        val originalCreatedAt = before.createdAt
        val originalAvailableAt = before.availableAt
        val originalEventCount = userReliabilityEventRepository.count()

        val updated = visualReviewService.makeAvailableNowForLocalDev(setup.matchId)

        assertEquals(originalCreatedAt.toInstant(), updated.createdAt.toInstant())
        assertTrue(updated.availableAt.isBefore(originalAvailableAt))
        assertEquals(updated.availableAt.plusMinutes(1440).toInstant(), updated.expiresAt?.toInstant())
        assertTrue(updated.reminderEligibleAt?.isAfter(updated.availableAt) == true)
        assertEquals(originalEventCount, userReliabilityEventRepository.count())
        assertTrue(homeStatusService.getOrCreateStatus(setup.userAId).dirty)
        assertTrue(homeStatusService.getOrCreateStatus(setup.userBId).dirty)
    }

    @Test
    fun `local dev availability override is idempotent once review is available`() {
        val setup = createMatchInVisualPhase()
        val before = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        val originalExpiresAt = before.expiresAt

        val updated = visualReviewService.makeAvailableNowForLocalDev(setup.matchId)

        assertEquals(originalExpiresAt?.toInstant(), updated.expiresAt?.toInstant())
    }

    @Test
    fun `local dev availability override does not revive expired review`() {
        val setup = createMatchInVisualPhase()
        visualReviewRepository.updateExpiresAtByMatchId(setup.matchId, OffsetDateTime.now().minusSeconds(1))

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.makeAvailableNowForLocalDev(setup.matchId)
        }

        assertEquals(DomainErrorCode.VISUAL_REVIEW_EXPIRED, exception.code)
    }

    @Test
    fun `local dev availability override rejects missing review`() {
        val setup = createMatchWithFirstChat()
        val match = matchService.findByIdOrThrow(setup.matchId)
        match.state = MatchState.VISUAL_PHASE
        matchRepository.saveAndFlush(match)

        assertThrows<NoSuchElementException> {
            visualReviewService.makeAvailableNowForLocalDev(setup.matchId)
        }
    }

    @Test
    fun `local dev availability override rejects match outside visual phase without mutation`() {
        val setup = createMatchInVisualPhase()
        val match = matchService.findByIdOrThrow(setup.matchId)
        match.state = MatchState.VISUAL_REJECTED
        matchRepository.saveAndFlush(match)
        val before = visualReviewService.findByMatchIdOrThrow(setup.matchId).availableAt

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.makeAvailableNowForLocalDev(setup.matchId)
        }

        assertEquals(DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE, exception.code)
        assertEquals(before.toInstant(), visualReviewService.findByMatchIdOrThrow(setup.matchId).availableAt.toInstant())
    }

    @Test
    fun `visual content access is allowed in visual phase before expiration`() {
        val setup = createMatchInVisualPhase()
        visualReviewRepository.updateExpiresAtByMatchId(setup.matchId, OffsetDateTime.now().plusMinutes(5))

        val access = visualReviewService.requireVisualContentAccess(setup.matchId, setup.userAId)

        assertEquals(setup.matchId, access.match.id)
        assertEquals(setup.matchId, access.review.matchId)
    }

    @Test
    fun `visual content access is denied in visual phase after wall clock expiration`() {
        val setup = createMatchInVisualPhase()
        visualReviewRepository.updateExpiresAtByMatchId(setup.matchId, OffsetDateTime.now().minusSeconds(1))

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.requireVisualContentAccess(setup.matchId, setup.userAId)
        }

        assertEquals(DomainErrorCode.VISUAL_REVIEW_EXPIRED, exception.code)
    }

    @Test
    fun `visual content access is denied in visual phase at exact expiration`() {
        val setup = createMatchInVisualPhase()
        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)

        val exception = assertThrows<DomainConflictException> {
            visualResourceAccessPolicyForTest().requireCanAccess(
                matchId = setup.matchId,
                requestingUserId = setup.userAId,
                now = review.expiresAt ?: error("Visual review expiration was not set")
            )
        }

        assertEquals(DomainErrorCode.VISUAL_REVIEW_EXPIRED, exception.code)
    }

    @Test
    fun `visual content access is denied in visual phase with null expiration`() {
        val setup = createMatchInVisualPhase()
        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        review.expiresAt = null
        visualReviewRepository.saveAndFlush(review)

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.requireVisualContentAccess(setup.matchId, setup.userAId)
        }

        assertEquals(DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE, exception.code)
    }

    @ParameterizedTest
    @EnumSource(
        value = ConnectionState::class,
        names = [
            "SCHEDULING_PENDING",
            "SCHEDULING_PHASE",
            "SECOND_CHAT_SCHEDULED",
            "SECOND_CHAT_AVAILABLE",
            "SECOND_CHAT"
        ]
    )
    fun `visual content access is allowed after visual approval for active connection states`(
        connectionState: ConnectionState
    ) {
        val setup = createVisualApprovedConnection(connectionState)

        val access = visualReviewService.requireVisualContentAccess(setup.matchId, setup.userAId)

        assertEquals(connectionState, access.connection?.state)
    }

    @Test
    fun `visual content access is denied after visual approval for closed connection`() {
        val setup = createVisualApprovedConnection(ConnectionState.CLOSED)

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.requireVisualContentAccess(setup.matchId, setup.userAId)
        }

        assertEquals(DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE, exception.code)
    }

    @Test
    fun `visual content access is denied after visual approval without connection`() {
        val setup = createMatchInVisualPhase()
        val match = matchService.findByIdOrThrow(setup.matchId)
        match.state = MatchState.VISUAL_APPROVED
        matchRepository.saveAndFlush(match)

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.requireVisualContentAccess(setup.matchId, setup.userAId)
        }

        assertEquals(DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE, exception.code)
    }

    @ParameterizedTest
    @EnumSource(
        value = MatchState::class,
        names = ["CHAT_ACTIVE", "CHAT_REJECTED", "VISUAL_REJECTED", "EXPIRED"]
    )
    fun `visual content access is denied for terminal or pre visual match states`(
        matchState: MatchState
    ) {
        val setup = createMatchInVisualPhase()
        val match = matchService.findByIdOrThrow(setup.matchId)
        match.state = matchState
        matchRepository.saveAndFlush(match)

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.requireVisualContentAccess(setup.matchId, setup.userAId)
        }

        assertEquals(DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE, exception.code)
    }

    @Test
    fun `visual content access is denied for blocked participant pair`() {
        val setup = createMatchInVisualPhase()
        userBlockService.blockUser(setup.userAId, setup.userBId, com.reals.backend.domain.UserBlockSource.MANUAL)

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.requireVisualContentAccess(setup.matchId, setup.userAId)
        }

        assertEquals(DomainErrorCode.USER_PAIR_BLOCKED, exception.code)
    }

    @Test
    fun `visual content access is denied for unrelated user`() {
        val setup = createMatchInVisualPhase()
        val stranger = userService.createUser("visual-policy-stranger-${UUID.randomUUID()}@example.com")

        assertThrows<AccessDeniedException> {
            visualReviewService.requireVisualContentAccess(setup.matchId, stranger.id)
        }
    }

    @Test
    fun `denied partner message read does not mutate read timestamp`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")
        visualReviewRepository.updateExpiresAtByMatchId(setup.matchId, OffsetDateTime.now().minusSeconds(1))

        assertThrows<DomainConflictException> {
            visualReviewService.getPartnerMessage(setup.matchId, setup.userAId)
        }

        assertNull(visualReviewService.findByMatchIdOrThrow(setup.matchId).personalMessageBReadByAAt)
    }

    @Test
    fun `denied partner message read with null visual expiration does not mutate read timestamp`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")
        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        review.expiresAt = null
        visualReviewRepository.saveAndFlush(review)

        assertThrows<DomainConflictException> {
            visualReviewService.getPartnerMessage(setup.matchId, setup.userAId)
        }

        assertNull(visualReviewService.findByMatchIdOrThrow(setup.matchId).personalMessageBReadByAAt)
    }

    @Test
    fun `record personal message stores first message for user A`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Hola despues del reveal")

        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        assertEquals("Hola despues del reveal", review.personalMessageA)
        assertNull(review.personalMessageB)
    }

    @Test
    fun `record personal message stores first message for user B`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Sigamos hablando")

        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        assertNull(review.personalMessageA)
        assertEquals("Sigamos hablando", review.personalMessageB)
    }

    @Test
    fun `record personal message rejects second message from same user`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Primer mensaje")

        assertThrows<IllegalStateException> {
            visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Segundo mensaje")
        }

        assertEquals(
            "Primer mensaje",
            visualReviewService.findByMatchIdOrThrow(setup.matchId).personalMessageA
        )
    }

    @Test
    fun `record personal message rejects invalid message`() {
        val setup = createMatchInVisualPhase()

        assertThrows<IllegalArgumentException> {
            visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "   ")
        }
        assertThrows<IllegalArgumentException> {
            visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "a".repeat(281))
        }
        assertThrows<IllegalArgumentException> {
            visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "<b>hola</b>")
        }
    }

    @Test
    fun `record personal message rejects non participant`() {
        val setup = createMatchInVisualPhase()
        val stranger = userService.createUser("visual-message-stranger-${UUID.randomUUID()}@example.com")

        assertThrows<AccessDeniedException> {
            visualReviewService.recordPersonalMessage(setup.matchId, stranger.id, "No pertenezco al match")
        }
    }

    @Test
    fun `get partner message marks message as read`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Mensaje A")
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")

        assertEquals("Mensaje B", visualReviewService.getPartnerMessage(setup.matchId, setup.userAId))
        assertEquals("Mensaje A", visualReviewService.getPartnerMessage(setup.matchId, setup.userBId))

        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        assertNotNull(review.personalMessageBReadByAAt)
        assertNotNull(review.personalMessageAReadByBAt)
    }

    @Test
    fun `personal message status reports no partner message as read and not required`() {
        val setup = createMatchInVisualPhase()

        val status = visualReviewService.getPersonalMessageStatusForUser(
            matchId = setup.matchId,
            userId = setup.userAId
        )

        assertFalse(status.partnerPersonalMessageSubmitted)
        assertTrue(status.partnerPersonalMessageRead)
        assertFalse(status.decisionRequiresPartnerPersonalMessageRead)
    }

    @Test
    fun `personal message status reports unread partner message as nonblocking`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")

        val status = visualReviewService.getPersonalMessageStatusForUser(
            matchId = setup.matchId,
            userId = setup.userAId
        )

        assertTrue(status.partnerPersonalMessageSubmitted)
        assertFalse(status.partnerPersonalMessageRead)
        assertFalse(status.decisionRequiresPartnerPersonalMessageRead)
    }

    @Test
    fun `personal message status reports read partner message as not required`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")

        visualReviewService.getPartnerMessage(setup.matchId, setup.userAId)

        val status = visualReviewService.getPersonalMessageStatusForUser(
            matchId = setup.matchId,
            userId = setup.userAId
        )

        assertTrue(status.partnerPersonalMessageSubmitted)
        assertTrue(status.partnerPersonalMessageRead)
        assertFalse(status.decisionRequiresPartnerPersonalMessageRead)
    }

    @Test
    fun `personal message status lookup does not mark partner message as read`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")

        visualReviewService.getPersonalMessageStatusForUser(
            matchId = setup.matchId,
            userId = setup.userAId
        )

        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        assertNull(review.personalMessageBReadByAAt)
    }

    @Test
    fun `visual approval succeeds when partner message is unread`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Me gustaria seguir")

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)

        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        assertEquals(
            VisualDecision.APPROVED,
            review.userAVisualDecision
        )
        assertNull(review.personalMessageBReadByAAt)
    }

    @Test
    fun `visual rejection succeeds when partner message is unread`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Me gustaria seguir")

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.REJECTED)

        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)
        assertEquals(
            VisualDecision.REJECTED,
            review.userAVisualDecision
        )
        assertNull(review.personalMessageBReadByAAt)
    }

    @Test
    fun `visual approval succeeds when no partner message exists`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)

        assertEquals(
            VisualDecision.APPROVED,
            visualReviewService.findByMatchIdOrThrow(setup.matchId).userAVisualDecision
        )
    }

    @Test
    fun `visual rejection succeeds when no partner message exists`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.REJECTED)

        assertEquals(
            VisualDecision.REJECTED,
            visualReviewService.findByMatchIdOrThrow(setup.matchId).userAVisualDecision
        )
    }

    @Test
    fun `replaying same visual decision remains idempotent`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)

        assertEquals(
            VisualDecision.APPROVED,
            visualReviewService.findByMatchIdOrThrow(setup.matchId).userAVisualDecision
        )
    }

    @Test
    fun `changing recorded visual decision remains rejected`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)

        assertThrows<IllegalStateException> {
            visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.REJECTED)
        }
        assertEquals(
            VisualDecision.APPROVED,
            visualReviewService.findByMatchIdOrThrow(setup.matchId).userAVisualDecision
        )
    }

    @Test
    fun `expired visual review still rejects new decisions`() {
        val setup = createMatchInVisualPhase()
        visualReviewRepository.updateExpiresAtByMatchId(setup.matchId, OffsetDateTime.now().minusSeconds(1))

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        }

        assertEquals(DomainErrorCode.VISUAL_REVIEW_EXPIRED, exception.code)
    }

    @Test
    fun `approval of blocked pair remains rejected`() {
        val setup = createMatchInVisualPhase()
        userBlockService.blockUser(setup.userAId, setup.userBId, com.reals.backend.domain.UserBlockSource.MANUAL)

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        }

        assertEquals(DomainErrorCode.USER_PAIR_BLOCKED, exception.code)
        assertNull(visualReviewService.findByMatchIdOrThrow(setup.matchId).userAVisualDecision)
    }

    @Test
    fun `both visual approvals create pending scheduling connection`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")
        val review = visualReviewService.findByMatchIdOrThrow(setup.matchId)

        assertEquals(MatchState.VISUAL_APPROVED, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(ConnectionState.SCHEDULING_PENDING, connection.state)
        assertTrue(review.messagesVisible)
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    fun `visual approval creates connection when one participant is exactly at connection admission limit`() {
        val setup = createMatchInVisualPhase()

        repeat(2) { index ->
            createConnectionForFemaleParticipant(setup.userAId, "visual-at-limit-$index")
        }

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")

        assertEquals(MatchState.VISUAL_APPROVED, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(ConnectionState.SCHEDULING_PENDING, connection.state)
        assertEquals(3, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    fun `visual approval creates connection when one participant is above connection admission limit`() {
        val setup = createMatchInVisualPhase()

        repeat(3) { index ->
            createConnectionForFemaleParticipant(setup.userAId, "visual-above-limit-$index")
        }

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        assertNotNull(connectionRepository.findByMatchId(setup.matchId))
        assertEquals(MatchState.VISUAL_APPROVED, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(4, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
    }

    @Test
    fun `visual approval creates connection when both participants are at or above connection admission limit`() {
        val setup = createMatchInVisualPhase()

        repeat(2) { index ->
            createConnectionForFemaleParticipant(setup.userAId, "visual-both-a-$index")
        }
        repeat(3) { index ->
            createConnectionForMaleParticipant(setup.userBId, "visual-both-b-$index")
        }

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        assertNotNull(connectionRepository.findByMatchId(setup.matchId))
        assertEquals(3, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertEquals(4, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    fun `connection overshoot blocks matchmaking until active locks fall below limit`() {
        val setup = createMatchInVisualPhase()
        val existingConnections =
            (0 until 2).map { index ->
                createConnectionForFemaleParticipant(setup.userAId, "visual-overshoot-$index")
            }

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        assertEquals(3, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertConnectionLimitBlocksSearch(setup.userAId)

        connectionService.closeConnection(existingConnections[0])

        assertEquals(2, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertConnectionLimitBlocksSearch(setup.userAId)

        connectionService.closeConnection(existingConnections[1])

        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(setup.userAId)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertTrue(availability.canSearch)
        assertNull(availability.blockedReason)
    }

    private fun createVisualApprovedConnection(
        connectionState: ConnectionState
    ): ConnectionFixture {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")
        connection.state = connectionState
        connection.updatedAt = OffsetDateTime.now()
        connectionRepository.saveAndFlush(connection)

        return ConnectionFixture(
            userAId = setup.userAId,
            userBId = setup.userBId,
            matchId = setup.matchId,
            connectionId = connection.id
        )
    }

    private fun visualResourceAccessPolicyForTest(): VisualResourceAccessPolicy =
        VisualResourceAccessPolicy(
            matchService = matchService,
            visualReviewRepository = visualReviewRepository,
            connectionRepository = connectionRepository,
            userBlockService = userBlockService
        )

    private fun createConnectionForFemaleParticipant(
        userId: UUID,
        prefix: String
    ): UUID {
        val partnerId =
            createActiveProfile(
                email = "$prefix-${UUID.randomUUID()}@example.com",
                displayName = "$prefix partner",
                gender = Gender.MALE,
                lookingForGenders = setOf(Gender.FEMALE)
            )
        val match = matchService.createMatch(userId, partnerId)
        return connectionService.createFromMatch(match).id
    }

    private fun createConnectionForMaleParticipant(
        userId: UUID,
        prefix: String
    ): UUID {
        val partnerId =
            createActiveProfile(
                email = "$prefix-${UUID.randomUUID()}@example.com",
                displayName = "$prefix partner",
                gender = Gender.FEMALE,
                lookingForGenders = setOf(Gender.MALE)
            )
        val match = matchService.createMatch(partnerId, userId)
        return connectionService.createFromMatch(match).id
    }

    private fun assertConnectionLimitBlocksSearch(userId: UUID) {
        val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(userId)

        assertFalse(availability.canSearch)
        assertEquals(DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED.name, availability.blockedReason?.code)
    }

}
