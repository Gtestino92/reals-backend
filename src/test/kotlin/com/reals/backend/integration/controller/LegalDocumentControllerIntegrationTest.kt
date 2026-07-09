package com.reals.backend.integration.controller

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.domain.UserLegalDocumentAction
import com.reals.backend.integration.ControllerIT
import com.reals.backend.repository.UserLegalDocumentActionRepository
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals

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
class LegalDocumentControllerIntegrationTest : ControllerIT() {

    private val termsContentSha256 = "78829bddbdbf5f73c35af82b61cc1ae3c81ecac78853a18e007450c0e1a858f3"

    @Autowired
    private lateinit var legalDocumentActionRepository: UserLegalDocumentActionRepository

    @Test
    fun `current legal documents are public and deterministic`() {
        mockMvc.perform(get("/api/legal/documents/current"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.documents.length()", equalTo(2)))
            .andExpect(jsonPath("$.documents[0].type", equalTo("TERMS_OF_USE")))
            .andExpect(jsonPath("$.documents[0].version", equalTo("2026-07-01-test")))
            .andExpect(jsonPath("$.documents[0].url", equalTo("https://example.test/terms")))
            .andExpect(jsonPath("$.documents[0].requiredAction", equalTo("ACCEPTED")))
            .andExpect(jsonPath("$.documents[0].contentSha256").doesNotExist())
            .andExpect(jsonPath("$.documents[1].type", equalTo("PRIVACY_NOTICE")))
            .andExpect(jsonPath("$.documents[1].version", equalTo("2026-07-01-test")))
            .andExpect(jsonPath("$.documents[1].url", equalTo("https://example.test/privacy")))
            .andExpect(jsonPath("$.documents[1].requiredAction", equalTo("ACKNOWLEDGED")))
            .andExpect(jsonPath("$.documents[1].contentSha256").doesNotExist())
    }

    @Test
    fun `legal status is initially unsatisfied and then satisfied after valid action`() {
        val user = userService.createUser("legal-status-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/me/legal-status")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.requirementsSatisfied", equalTo(false)))
            .andExpect(jsonPath("$.documents[0].type", equalTo("TERMS_OF_USE")))
            .andExpect(jsonPath("$.documents[0].recordedAction").doesNotExist())
            .andExpect(jsonPath("$.documents[0].actedAt").doesNotExist())
            .andExpect(jsonPath("$.documents[0].satisfied", equalTo(false)))

        mockMvc.perform(
            post("/api/me/legal-document-actions")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "documentType": "TERMS_OF_USE",
                      "documentVersion": "2026-07-01-test",
                      "action": "ACCEPTED"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id", notNullValue()))
            .andExpect(jsonPath("$.documentType", equalTo("TERMS_OF_USE")))
            .andExpect(jsonPath("$.documentVersion", equalTo("2026-07-01-test")))
            .andExpect(jsonPath("$.action", equalTo("ACCEPTED")))
            .andExpect(jsonPath("$.actedAt", notNullValue()))
            .andExpect(jsonPath("$.userId").doesNotExist())
            .andExpect(jsonPath("$.documentContentSha256").doesNotExist())
            .andExpect(jsonPath("$.contentSha256").doesNotExist())

        val storedAction = legalDocumentActionRepository
            .findByUserIdAndDocumentTypeAndDocumentVersion(
                userId = user.id,
                documentType = LegalDocumentType.TERMS_OF_USE,
                documentVersion = "2026-07-01-test"
            )
            ?: error("Legal document action was not stored")
        assertEquals(user.id, storedAction.userId)
        assertEquals(termsContentSha256, storedAction.documentContentSha256)

        mockMvc.perform(
            get("/api/me/legal-status")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.requirementsSatisfied", equalTo(false)))
            .andExpect(jsonPath("$.documents[0].recordedAction", equalTo("ACCEPTED")))
            .andExpect(jsonPath("$.documents[0].actedAt", notNullValue()))
            .andExpect(jsonPath("$.documents[0].satisfied", equalTo(true)))
            .andExpect(jsonPath("$.documents[0].documentContentSha256").doesNotExist())
            .andExpect(jsonPath("$.documents[0].contentSha256").doesNotExist())
            .andExpect(jsonPath("$.documents[1].satisfied", equalTo(false)))
    }

    @Test
    fun `historical legal document action does not satisfy current version`() {
        val user = userService.createUser("legal-history-${UUID.randomUUID()}@example.com")
        legalDocumentActionRepository.save(
            UserLegalDocumentAction(
                userId = user.id,
                documentType = LegalDocumentType.TERMS_OF_USE,
                documentVersion = "2026-06-01-test",
                documentContentSha256 = termsContentSha256,
                action = LegalDocumentAction.ACCEPTED
            )
        )

        mockMvc.perform(
            get("/api/me/legal-status")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.documents[0].version", equalTo("2026-07-01-test")))
            .andExpect(jsonPath("$.documents[0].recordedAction").doesNotExist())
            .andExpect(jsonPath("$.documents[0].satisfied", equalTo(false)))
    }

    @Test
    fun `legacy legal document action without content hash does not satisfy current document`() {
        val user = userService.createUser("legal-legacy-hash-${UUID.randomUUID()}@example.com")
        legalDocumentActionRepository.save(
            UserLegalDocumentAction(
                userId = user.id,
                documentType = LegalDocumentType.TERMS_OF_USE,
                documentVersion = "2026-07-01-test",
                action = LegalDocumentAction.ACCEPTED
            )
        )

        mockMvc.perform(
            get("/api/me/legal-status")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.documents[0].recordedAction", equalTo("ACCEPTED")))
            .andExpect(jsonPath("$.documents[0].satisfied", equalTo(false)))
    }

    @Test
    fun `legal document action with different content hash does not satisfy current document`() {
        val user = userService.createUser("legal-different-hash-${UUID.randomUUID()}@example.com")
        legalDocumentActionRepository.save(
            UserLegalDocumentAction(
                userId = user.id,
                documentType = LegalDocumentType.TERMS_OF_USE,
                documentVersion = "2026-07-01-test",
                documentContentSha256 = "0".repeat(64),
                action = LegalDocumentAction.ACCEPTED
            )
        )

        mockMvc.perform(
            get("/api/me/legal-status")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.documents[0].recordedAction", equalTo("ACCEPTED")))
            .andExpect(jsonPath("$.documents[0].satisfied", equalTo(false)))
    }

    @Test
    fun `identical replay is idempotent and does not duplicate row or audit event`() {
        val user = userService.createUser("legal-replay-${UUID.randomUUID()}@example.com")
        val requestBody =
            """
            {
              "documentType": "TERMS_OF_USE",
              "documentVersion": "2026-07-01-test",
              "action": "ACCEPTED"
            }
            """.trimIndent()

        mockMvc.perform(
            post("/api/me/legal-document-actions")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(requestBody)
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/me/legal-document-actions")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(requestBody)
        )
            .andExpect(status().isOk)

        assertEquals(
            1,
            legalDocumentActionRepository.findByUserId(user.id)
                .count { it.documentType == LegalDocumentType.TERMS_OF_USE && it.documentVersion == "2026-07-01-test" }
        )

        val auditEvents = auditEventRepository.findAll()
            .filter {
                it.eventType == AuditEventType.LEGAL_DOCUMENT_ACTION_RECORDED &&
                    it.aggregateId == user.id
            }

        assertEquals(1, auditEvents.size)
        val auditEvent = auditEvents.single()
        assertEquals(AuditAggregateType.USER, auditEvent.aggregateType)
        assertEquals(user.id, auditEvent.actorUserId)
        assertEquals(user.id, auditEvent.aggregateId)
        kotlin.test.assertTrue(auditEvent.metadataJson?.contains("TERMS_OF_USE") == true)
        kotlin.test.assertTrue(auditEvent.metadataJson?.contains("2026-07-01-test") == true)
        kotlin.test.assertTrue(auditEvent.metadataJson?.contains("ACCEPTED") == true)
        kotlin.test.assertTrue(auditEvent.metadataJson?.contains(termsContentSha256) == true)
    }

    @Test
    fun `same type and version with different historical content hash fails without rewriting action`() {
        val user = userService.createUser("legal-hash-invariant-${UUID.randomUUID()}@example.com")
        val historicalActedAt = java.time.OffsetDateTime.parse("2026-07-01T00:00:00Z")
        val historicalHash = "0".repeat(64)
        val existing = legalDocumentActionRepository.saveAndFlush(
            UserLegalDocumentAction(
                userId = user.id,
                documentType = LegalDocumentType.TERMS_OF_USE,
                documentVersion = "2026-07-01-test",
                documentContentSha256 = historicalHash,
                action = LegalDocumentAction.ACCEPTED,
                actedAt = historicalActedAt
            )
        )

        mockMvc.perform(
            post("/api/me/legal-document-actions")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "documentType": "TERMS_OF_USE",
                      "documentVersion": "2026-07-01-test",
                      "action": "ACCEPTED"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("DOMAIN_CONFLICT")))

        val stored = legalDocumentActionRepository.findById(existing.id).orElseThrow()
        assertEquals(historicalHash, stored.documentContentSha256)
        assertEquals(historicalActedAt, stored.actedAt)
        assertEquals(
            1,
            legalDocumentActionRepository.findByUserId(user.id)
                .count { it.documentType == LegalDocumentType.TERMS_OF_USE && it.documentVersion == "2026-07-01-test" }
        )
        assertEquals(
            0,
            auditEventRepository.findAll().count {
                it.eventType == AuditEventType.LEGAL_DOCUMENT_ACTION_RECORDED &&
                    it.aggregateId == user.id
            }
        )
    }

    @Test
    fun `stale legal document version returns conflict`() {
        val user = userService.createUser("legal-stale-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/me/legal-document-actions")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "documentType": "TERMS_OF_USE",
                      "documentVersion": "2026-06-01-test",
                      "action": "ACCEPTED"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_DOCUMENT_VERSION_NOT_CURRENT")))
    }

    @Test
    fun `wrong legal document action returns bad request`() {
        val user = userService.createUser("legal-wrong-action-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/me/legal-document-actions")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "documentType": "TERMS_OF_USE",
                      "documentVersion": "2026-07-01-test",
                      "action": "ACKNOWLEDGED"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_DOCUMENT_ACTION_INVALID")))
    }

    @Test
    fun `unknown legal document type returns not found`() {
        val user = userService.createUser("legal-unknown-type-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/me/legal-document-actions")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "documentType": "COMMUNITY_GUIDELINES",
                      "documentVersion": "2026-07-01-test",
                      "action": "ACKNOWLEDGED"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code", equalTo("LEGAL_DOCUMENT_NOT_FOUND")))
    }

    @Test
    fun `malformed enum value uses malformed request behavior`() {
        val user = userService.createUser("legal-malformed-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/me/legal-document-actions")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "documentType": "BAD_TYPE",
                      "documentVersion": "2026-07-01-test",
                      "action": "ACCEPTED"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("MALFORMED_REQUEST")))
    }
}
