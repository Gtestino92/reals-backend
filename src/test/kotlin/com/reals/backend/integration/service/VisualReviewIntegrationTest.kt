package com.reals.backend.integration.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

class VisualReviewIntegrationTest : BaseIT() {

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
    fun `personal message status reports unread partner message as decision requirement`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")

        val status = visualReviewService.getPersonalMessageStatusForUser(
            matchId = setup.matchId,
            userId = setup.userAId
        )

        assertTrue(status.partnerPersonalMessageSubmitted)
        assertFalse(status.partnerPersonalMessageRead)
        assertTrue(status.decisionRequiresPartnerPersonalMessageRead)
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
    fun `visual approval returns stable conflict when partner message is unread`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Me gustaria seguir")

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        }
        assertEquals(DomainErrorCode.VISUAL_REVIEW_PARTNER_MESSAGE_NOT_READ, exception.code)

        assertEquals("Me gustaria seguir", visualReviewService.getPartnerMessage(setup.matchId, setup.userAId))

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        assertEquals(
            VisualDecision.APPROVED,
            visualReviewService.findByMatchIdOrThrow(setup.matchId).userAVisualDecision
        )
    }

    @Test
    fun `visual rejection returns stable conflict when partner message is unread`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Me gustaria seguir")

        val exception = assertThrows<DomainConflictException> {
            visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.REJECTED)
        }
        assertEquals(DomainErrorCode.VISUAL_REVIEW_PARTNER_MESSAGE_NOT_READ, exception.code)

        assertEquals("Me gustaria seguir", visualReviewService.getPartnerMessage(setup.matchId, setup.userAId))

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.REJECTED)
        assertEquals(
            VisualDecision.REJECTED,
            visualReviewService.findByMatchIdOrThrow(setup.matchId).userAVisualDecision
        )
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

}
