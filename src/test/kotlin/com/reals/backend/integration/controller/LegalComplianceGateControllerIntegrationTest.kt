package com.reals.backend.integration.controller

import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.LegalDocumentService
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestPropertySource(
    properties = [
        "legal.documents[0].type=TERMS_OF_USE",
        "legal.documents[0].version=2026-07-01-test",
        "legal.documents[0].url=https://example.test/terms",
        "legal.documents[0].content-sha256=78829bddbdbf5f73c35af82b61cc1ae3c81ecac78853a18e007450c0e1a858f3",
        "legal.documents[0].required-action=ACCEPTED",
        "legal.documents[1].type=PRIVACY_NOTICE",
        "legal.documents[1].version=2026-07-01-test",
        "legal.documents[1].url=https://example.test/privacy",
        "legal.documents[1].content-sha256=57da1b2c78208dce6757e540b82a55589facc8bc477b0a961b568c424e9c2bda",
        "legal.documents[1].required-action=ACKNOWLEDGED"
    ]
)
class LegalComplianceGateControllerIntegrationTest : ControllerIT() {

    @Autowired
    private lateinit var legalDocumentService: LegalDocumentService

    @Test
    fun `profile creation is blocked until current legal requirements are satisfied`() {
        val user = userService.createUser("legal-gate-profile-${UUID.randomUUID()}@example.com")
        val body = profileCreateRequestBody()

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(body)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_ACTION_REQUIRED")))

        assertNull(profileRepository.findByUserId(user.id))

        satisfyCurrentLegalRequirements(user.id)

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.userId", equalTo(user.id.toString())))
    }

    @Test
    fun `positive first chat continuation is gated and does not persist approval`() {
        val setup = createMatchWithFirstChat("legal-gate-chat-approve")

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/chat-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_ACTION_REQUIRED")))

        val decision = chatDecisionRepository.findByMatchId(setup.matchId)
        assertNull(decision?.userADecision)
    }

    @Test
    fun `negative first chat continuation remains available without legal compliance`() {
        val setup = createMatchWithFirstChat("legal-gate-chat-reject")

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/chat-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"REJECTED"}""")
        )
            .andExpect(status().isOk)

        val decision = chatDecisionRepository.findByMatchId(setup.matchId)
        assertNull(decision)
    }

    @Test
    fun `positive visual decision is gated and does not persist approval`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_ACTION_REQUIRED")))

        val visualReview = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Visual review was not created")
        assertNull(visualReview.userAVisualDecision)
    }

    @Test
    fun `negative visual decision remains available without legal compliance`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"REJECTED"}""")
        )
            .andExpect(status().isOk)

        val visualReview = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Visual review was not created")
        assertEquals(VisualDecision.REJECTED, visualReview.userAVisualDecision)
    }

    @Test
    fun `scheduling proposal is blocked until current legal requirements are satisfied`() {
        val setup = createConnectionInSchedulingPhase()
        val proposedDateTime = futureHalfHourSlot()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"proposedDateTimes":["$proposedDateTime"]}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_ACTION_REQUIRED")))

        assertEquals(0, proposalRepository.findByConnectionId(setup.connectionId).size)
    }

    private fun satisfyCurrentLegalRequirements(userId: UUID) {
        legalDocumentService.recordAction(
            userId = userId,
            documentType = LegalDocumentType.TERMS_OF_USE,
            documentVersion = "2026-07-01-test",
            action = LegalDocumentAction.ACCEPTED
        )
        legalDocumentService.recordAction(
            userId = userId,
            documentType = LegalDocumentType.PRIVACY_NOTICE,
            documentVersion = "2026-07-01-test",
            action = LegalDocumentAction.ACKNOWLEDGED
        )
    }

    private fun profileCreateRequestBody(): String =
        """
        {
          "displayName": "Legal Gate",
          "birthDate": "${LocalDate.of(1995, 1, 1)}",
          "gender": "FEMALE",
          "lookingForGenders": ["MALE"],
          "intention": "DATE",
          "city": "Buenos Aires",
          "country": "AR",
          "bio": "Profile after legal compliance",
          "preferredMinAge": 18,
          "preferredMaxAge": 99,
          "maxDistanceKm": 50
        }
        """.trimIndent()
}
