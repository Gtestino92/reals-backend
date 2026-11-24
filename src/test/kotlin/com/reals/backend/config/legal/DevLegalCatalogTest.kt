package com.reals.backend.config.legal

import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.domain.UserLegalDocumentAction
import com.reals.backend.repository.UserLegalDocumentActionRepository
import com.reals.backend.service.LegalDocumentService
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.TestConfiguration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DevLegalCatalogTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Config

    @Test
    fun `only explicitly enabled development binds all three byte verified documents`() {
        for ((profiles, count) in mapOf(
            "dev" to 0, "prod" to 0, "prod,dev-legal-fixtures" to 0,
            "dev,prod,dev-legal-fixtures" to 0, "dev,dev-legal-fixtures" to 3
        )) {
            SpringApplicationBuilder(Config::class.java).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=$profiles").use { context ->
                    val catalog = Binder.get(context.environment)
                        .bindOrCreate("legal", LegalDocumentProperties::class.java)
                    assertEquals(count, catalog.documents.size, profiles)
                    LegalDocumentIntegrityVerifier(catalog, context).verifyCurrentDocuments()
                }
        }
    }

    @Test
    fun `completed v1 account has three pending v2 documents`() {
        SpringApplicationBuilder(Config::class.java).web(WebApplicationType.NONE)
            .run("--spring.profiles.active=dev,dev-legal-fixtures").use { context ->
                val catalog = Binder.get(context.environment).bindOrCreate("legal", LegalDocumentProperties::class.java)
                val userId = UUID.randomUUID()
                val v1 = "dev-fixture-2026-11-23-v1"
                val previous = mapOf(
                    LegalDocumentType.TERMS_OF_USE to LegalDocumentAction.ACCEPTED,
                    LegalDocumentType.PRIVACY_NOTICE to LegalDocumentAction.ACKNOWLEDGED,
                    LegalDocumentType.COMMUNITY_GUIDELINES to LegalDocumentAction.ACCEPTED
                )
                val actions = previous.map { (type, action) ->
                    val path = LegalDocumentCanonicalResources.resourcePath(type, v1)
                    val hash = context.getResource("classpath:$path").inputStream.use { LegalDocumentSha256.hash(it.readBytes()) }
                    UserLegalDocumentAction(userId = userId, documentType = type, documentVersion = v1,
                        documentContentSha256 = hash, action = action)
                }
                val repository = mock(UserLegalDocumentActionRepository::class.java)
                `when`(repository.findByUserId(userId)).thenReturn(actions)
                val status = LegalDocumentService(catalog, repository, mock(), mock()).getStatus(userId)

                assertFalse(status.requirementsSatisfied)
                assertEquals(previous, status.documents.associate { it.document.type to it.document.requiredAction })
                for (entry in status.documents) {
                    assertEquals("dev-fixture-2026-11-24-v2", entry.document.version)
                    assertNull(entry.recordedAction)
                    assertFalse(entry.satisfied)
                }
            }
    }
}
