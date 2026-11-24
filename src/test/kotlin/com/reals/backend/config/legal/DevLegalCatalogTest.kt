package com.reals.backend.config.legal

import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.TestConfiguration
import kotlin.test.assertEquals

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
}
