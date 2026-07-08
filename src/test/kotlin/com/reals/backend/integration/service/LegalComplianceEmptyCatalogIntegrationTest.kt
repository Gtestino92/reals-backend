package com.reals.backend.integration.service

import com.reals.backend.integration.BaseIT
import com.reals.backend.service.LegalComplianceService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class LegalComplianceEmptyCatalogIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var legalComplianceService: LegalComplianceService

    @Test
    fun `empty legal document catalog passes legal compliance guard`() {
        val user = userService.createUser("legal-compliance-empty-${UUID.randomUUID()}@example.com")

        legalComplianceService.requireCurrentRequirementsSatisfied(user.id)
    }
}
