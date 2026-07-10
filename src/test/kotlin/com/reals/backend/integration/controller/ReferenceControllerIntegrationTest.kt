package com.reals.backend.integration.controller

import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.LegalDocumentService
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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
class ReferenceControllerIntegrationTest : ControllerIT() {

    @Autowired
    private lateinit var legalDocumentService: LegalDocumentService

    @Test
    fun `countries reference returns entries for authenticated user`() {
        val user = userService.createUser("countries-reference-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/reference/countries")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].code", notNullValue()))
            .andExpect(jsonPath("$[0].displayName", notNullValue()))
    }

    @Test
    fun `countries reference requires authentication`() {
        mockMvc.perform(get("/api/reference/countries"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code", equalTo("AUTHENTICATION_REQUIRED")))
    }

    @Test
    fun `countries reference does not require existing profile`() {
        val user = userService.createUser("countries-no-profile-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/reference/countries")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `countries reference does not require current legal actions`() {
        val user = userService.createUser("countries-legal-unsatisfied-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/reference/countries")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)

        legalDocumentService.recordAction(
            userId = user.id,
            documentType = LegalDocumentType.TERMS_OF_USE,
            documentVersion = "2026-07-01-test",
            action = LegalDocumentAction.ACCEPTED
        )

        mockMvc.perform(
            get("/api/reference/countries")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
    }
}
