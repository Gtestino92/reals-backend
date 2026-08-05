package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.LegalDocumentService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
class ProfileQuestionControllerIntegrationTest : ControllerIT() {
    @Autowired
    private lateinit var legalDocumentService: LegalDocumentService

    @Test
    fun `catalog endpoint response and order`() {
        val userId = createDraftProfile("profile-question-catalog")

        mockMvc.perform(
            get("/api/reference/profile-questions")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.catalogVersion", equalTo("2026-08-01")))
            .andExpect(jsonPath("$.questions.length()", equalTo(18)))
            .andExpect(jsonPath("$.questions[0].id", equalTo("PERFECT_SUNDAY_001")))
            .andExpect(jsonPath("$.questions[0].semanticVersion", equalTo(1)))
            .andExpect(jsonPath("$.questions[0].contentVersion", equalTo(1)))
            .andExpect(jsonPath("$.questions[0].prompt", equalTo("Mi domingo perfecto incluye...")))
            .andExpect(jsonPath("$.questions[0].displayOrder", equalTo(1)))
            .andExpect(content().string(not(containsString("active"))))
    }

    @Test
    fun `private CRUD and selection responses`() {
        val userId = createDraftProfile("profile-question-crud")
        satisfyCurrentLegalRequirements(userId)

        mockMvc.perform(
            get("/api/me/profile/question-answers")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answers.length()", equalTo(0)))

        mockMvc.perform(
            put("/api/me/profile/question-answers/PERFECT_SUNDAY_001")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"answer":"Café, piano y una caminata sin apuro."}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answers[0].questionId", equalTo("PERFECT_SUNDAY_001")))
            .andExpect(jsonPath("$.answers[0].questionSemanticVersion", equalTo(1)))
            .andExpect(jsonPath("$.answers[0].answer", equalTo("Café, piano y una caminata sin apuro.")))
            .andExpect(jsonPath("$.answers[0].selectedPosition").doesNotExist())
            .andExpect(jsonPath("$.answers[0].current", equalTo(true)))
            .andExpect(jsonPath("$.answers[0].createdAt").exists())
            .andExpect(jsonPath("$.answers[0].updatedAt").exists())

        mockMvc.perform(
            put("/api/me/profile/question-answers/LIFE_SOUNDTRACK_001")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"answer":"Música de domingo."}""")
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            put("/api/me/profile/question-selections")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"questionIds":["LIFE_SOUNDTRACK_001","PERFECT_SUNDAY_001"]}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answers[0].questionId", equalTo("PERFECT_SUNDAY_001")))
            .andExpect(jsonPath("$.answers[0].selectedPosition", equalTo(2)))
            .andExpect(jsonPath("$.answers[1].questionId", equalTo("LIFE_SOUNDTRACK_001")))
            .andExpect(jsonPath("$.answers[1].selectedPosition", equalTo(1)))

        mockMvc.perform(
            delete("/api/me/profile/question-answers/LIFE_SOUNDTRACK_001")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answers.length()", equalTo(1)))
            .andExpect(jsonPath("$.answers[0].selectedPosition", equalTo(1)))
    }

    @Test
    fun `authentication and legal boundaries`() {
        val userId = createDraftProfile("profile-question-legal")

        mockMvc.perform(get("/api/me/profile/question-answers"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code", equalTo("AUTHENTICATION_REQUIRED")))

        mockMvc.perform(
            get("/api/me/profile/question-answers")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            put("/api/me/profile/question-answers/PERFECT_SUNDAY_001")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"answer":"Respuesta privada"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_ACTION_REQUIRED")))

        satisfyCurrentLegalRequirements(userId)

        mockMvc.perform(
            put("/api/me/profile/question-answers/PERFECT_SUNDAY_001")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"answer":"Respuesta privada"}""")
        )
            .andExpect(status().isOk)

        val secondUserId = createDraftProfile("profile-question-legal-second")
        mockMvc.perform(
            put("/api/me/profile/question-selections")
                .with(authenticatedAs(secondUserId))
                .contentType(jsonContentType)
                .content("""{"questionIds":[]}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_ACTION_REQUIRED")))
    }

    @Test
    fun `validation errors use stable codes and do not include answer text`() {
        val userId = createDraftProfile("profile-question-validation-controller")
        satisfyCurrentLegalRequirements(userId)

        mockMvc.perform(
            put("/api/me/profile/question-answers/PERFECT_SUNDAY_001")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"answer":"secreto
visible"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("MALFORMED_REQUEST")))
            .andExpect(content().string(not(containsString("secreto"))))

        mockMvc.perform(
            put("/api/me/profile/question-answers/PERFECT_SUNDAY_001")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"answer":"<script>secreto</script>"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_QUESTION_ANSWER")))
            .andExpect(content().string(not(containsString("secreto"))))

        mockMvc.perform(
            put("/api/me/profile/question-selections")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"questionIds":["A","B","C","D"]}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_QUESTION_SELECTION_LIMIT_EXCEEDED")))
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
            displayName = "Profile Questions API",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            bio = "Profile question API profile",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )
        return user.id
    }
}
