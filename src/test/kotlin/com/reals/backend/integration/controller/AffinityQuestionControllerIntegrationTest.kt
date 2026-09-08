package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.LegalDocumentService
import com.reals.backend.service.affinity.AffinityAnswerPatch
import com.reals.backend.service.affinity.AffinityQuestionAnswerService
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

@TestPropertySource(
    properties = [
        "legal.documents[0].type=TERMS_OF_USE",
        "legal.documents[0].version=2026-07-01-test",
        "legal.documents[0].url=https://example.test/terms",
        "legal.documents[0].content-sha256=ff9fe114707d5bc600e3e7be9f48060f6507215784d6b6357f89317c0b965405",
        "legal.documents[0].required-action=ACCEPTED",
        "legal.documents[1].type=PRIVACY_NOTICE",
        "legal.documents[1].version=2026-07-01-test",
        "legal.documents[1].url=https://example.test/privacy",
        "legal.documents[1].content-sha256=d71901f8357a8d5923eef8c174f8c0eef90cbcbbc581b20cc926a74da6c4fe0c",
        "legal.documents[1].required-action=ACKNOWLEDGED"
    ]
)
class AffinityQuestionControllerIntegrationTest : ControllerIT() {
    @Autowired
    private lateinit var affinityQuestionAnswerService: AffinityQuestionAnswerService

    @Autowired
    private lateinit var legalDocumentService: LegalDocumentService

    @Test
    fun `reference catalog returns client-safe data without scoring internals`() {
        val userId = createDraftProfile("affinity-reference")

        mockMvc.perform(
            get("/api/reference/affinity-questions")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.catalogVersion", equalTo("2026.08.0")))
            .andExpect(jsonPath("$.categories[0].id", equalTo("CINEMA_SERIES_AND_STORIES")))
            .andExpect(jsonPath("$.questions[0].id", equalTo("CINEMA_IMPORTANCE_001")))
            .andExpect(content().string(not(containsString("rankingPolicy"))))
            .andExpect(content().string(not(containsString("conversationPolicy"))))
            .andExpect(content().string(not(containsString("matrix"))))
            .andExpect(content().string(not(containsString("maxContribution"))))
    }

    @Test
    fun `authenticated user reads own answers`() {
        val userId = createDraftProfile("affinity-read")
        affinityQuestionAnswerService.patchMyAnswers(
            userId = userId,
            patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "IMPORTANT"))
        )

        mockMvc.perform(
            get("/api/me/profile/affinity-answers")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answers[0].questionId", equalTo("CINEMA_IMPORTANCE_001")))
            .andExpect(jsonPath("$.answers[0].answerCode", equalTo("IMPORTANT")))
            .andExpect(content().string(not(containsString("rankingPolicy"))))
            .andExpect(content().string(not(containsString("matrix"))))
    }

    @Test
    fun `unauthenticated access rejected`() {
        mockMvc.perform(get("/api/me/profile/affinity-answers"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code", equalTo("AUTHENTICATION_REQUIRED")))
    }

    @Test
    fun `no arbitrary user read route exists`() {
        val userId = createDraftProfile("affinity-no-arbitrary-route")

        mockMvc.perform(
            get("/api/me/profile/affinity-answers/users/$userId")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `write operations require legal compliance`() {
        val userId = createDraftProfile("affinity-legal")

        mockMvc.perform(
            patch("/api/me/profile/affinity-answers")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"answers":[{"questionId":"CINEMA_IMPORTANCE_001","answerCode":"IMPORTANT"}]}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_ACTION_REQUIRED")))

        satisfyCurrentLegalRequirements(userId)

        mockMvc.perform(
            patch("/api/me/profile/affinity-answers")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"answers":[{"questionId":"CINEMA_IMPORTANCE_001","answerCode":"IMPORTANT"}]}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answers[0].answerCode", equalTo("IMPORTANT")))

        mockMvc.perform(
            delete("/api/me/profile/affinity-answers/CINEMA_IMPORTANCE_001")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answers.length()", equalTo(0)))
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

    private fun createDraftProfile(prefix: String): UUID {
        val user = userService.createUser("$prefix-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Affinity API",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            bio = "Affinity API profile",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )
        return user.id
    }
}
