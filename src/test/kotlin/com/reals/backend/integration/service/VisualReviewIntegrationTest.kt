package com.reals.backend.integration.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `approve visual review requires reading partner message when present`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Me gustaria seguir")

        assertThrows<IllegalStateException> {
            visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        }

        assertEquals("Me gustaria seguir", visualReviewService.getPartnerMessage(setup.matchId, setup.userAId))

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        assertEquals(
            VisualDecision.APPROVED,
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
