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
        "legal.documents[0].content-sha256=ff9fe114707d5bc600e3e7be9f48060f6507215784d6b6357f89317c0b965405",
        "legal.documents[0].required-action=ACCEPTED",
        "legal.documents[1].type=PRIVACY_NOTICE",
        "legal.documents[1].version=2026-07-01-test",
        "legal.documents[1].url=https://example.test/privacy",
        "legal.documents[1].content-sha256=d71901f8357a8d5923eef8c174f8c0eef90cbcbbc581b20cc926a74da6c4fe0c",
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
