package com.reals.backend.config.legal

import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LegalDocumentPropertiesTest {

    @Test
    fun `accepts valid configured content SHA-256`() {
        val properties = LegalDocumentProperties(
            documents = listOf(definition(contentSha256 = "a".repeat(64)))
        )

        kotlin.test.assertEquals(1, properties.currentDocuments().size)
    }

    @Test
    fun `rejects blank content SHA-256`() {
        assertThrows<IllegalArgumentException> {
            LegalDocumentProperties(
                documents = listOf(definition(contentSha256 = " "))
            )
        }
    }

    @Test
    fun `rejects short content SHA-256`() {
        assertThrows<IllegalArgumentException> {
            LegalDocumentProperties(
                documents = listOf(definition(contentSha256 = "a".repeat(63)))
            )
        }
    }

    @Test
    fun `rejects uppercase content SHA-256`() {
        assertThrows<IllegalArgumentException> {
            LegalDocumentProperties(
                documents = listOf(definition(contentSha256 = "A".repeat(64)))
            )
        }
    }

    @Test
    fun `rejects non-hex content SHA-256`() {
        assertThrows<IllegalArgumentException> {
            LegalDocumentProperties(
                documents = listOf(definition(contentSha256 = "g".repeat(64)))
            )
        }
    }

    @Test
    fun `rejects blank version`() {
        assertThrows<IllegalArgumentException> {
            LegalDocumentProperties(
                documents = listOf(definition(version = " "))
            )
        }
    }

    @Test
    fun `rejects unsafe version path values`() {
        listOf(
            "2026/07/01",
            "2026\\07\\01",
            "../2026-07-01",
            "2026-07-01..backup"
        ).forEach { unsafeVersion ->
            assertThrows<IllegalArgumentException> {
                LegalDocumentProperties(
                    documents = listOf(definition(version = unsafeVersion))
                )
            }
        }
    }

    @Test
    fun `rejects blank URL`() {
        assertThrows<IllegalArgumentException> {
            LegalDocumentProperties(
                documents = listOf(definition(url = " "))
            )
        }
    }

    @Test
    fun `rejects duplicate document type`() {
        assertThrows<IllegalArgumentException> {
            LegalDocumentProperties(
                documents = listOf(
                    definition(type = LegalDocumentType.TERMS_OF_USE),
                    definition(type = LegalDocumentType.TERMS_OF_USE, version = "2026-07-02-test")
                )
            )
        }
    }

    private fun definition(
        type: LegalDocumentType = LegalDocumentType.TERMS_OF_USE,
        version: String = "2026-07-01-test",
        url: String = "https://example.test/legal",
        contentSha256: String = "a".repeat(64),
        requiredAction: LegalDocumentAction = LegalDocumentAction.ACCEPTED
    ): LegalDocumentDefinition =
        LegalDocumentDefinition(
            type = type,
            version = version,
            url = url,
            contentSha256 = contentSha256,
            requiredAction = requiredAction
        )
}
