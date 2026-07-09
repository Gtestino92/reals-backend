package com.reals.backend.integration.service

import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.LegalComplianceService
import com.reals.backend.service.LegalDocumentService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
class LegalComplianceServiceIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var legalComplianceService: LegalComplianceService

    @Autowired
    private lateinit var legalDocumentService: LegalDocumentService

    @Test
    fun `unsatisfied user is blocked by legal compliance guard`() {
        val user = userService.createUser("legal-compliance-unsatisfied-${UUID.randomUUID()}@example.com")

        val exception = assertFailsWith<DomainConflictException> {
            legalComplianceService.requireCurrentRequirementsSatisfied(user.id)
        }

        assertEquals(DomainErrorCode.LEGAL_ACTION_REQUIRED, exception.code)
    }

    @Test
    fun `partially satisfied user is still blocked by legal compliance guard`() {
        val user = userService.createUser("legal-compliance-partial-${UUID.randomUUID()}@example.com")
        legalDocumentService.recordAction(
            userId = user.id,
            documentType = LegalDocumentType.TERMS_OF_USE,
            documentVersion = "2026-07-01-test",
            action = LegalDocumentAction.ACCEPTED
        )

        val exception = assertFailsWith<DomainConflictException> {
            legalComplianceService.requireCurrentRequirementsSatisfied(user.id)
        }

        assertEquals(DomainErrorCode.LEGAL_ACTION_REQUIRED, exception.code)
    }

    @Test
    fun `fully satisfied user passes legal compliance guard`() {
        val user = userService.createUser("legal-compliance-full-${UUID.randomUUID()}@example.com")
        legalDocumentService.recordAction(
            userId = user.id,
            documentType = LegalDocumentType.TERMS_OF_USE,
            documentVersion = "2026-07-01-test",
            action = LegalDocumentAction.ACCEPTED
        )
        legalDocumentService.recordAction(
            userId = user.id,
            documentType = LegalDocumentType.PRIVACY_NOTICE,
            documentVersion = "2026-07-01-test",
            action = LegalDocumentAction.ACKNOWLEDGED
        )

        legalComplianceService.requireCurrentRequirementsSatisfied(user.id)
    }
}
