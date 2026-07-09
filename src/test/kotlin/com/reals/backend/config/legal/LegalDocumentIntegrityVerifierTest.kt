package com.reals.backend.config.legal

import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.DefaultResourceLoader
import kotlin.test.assertTrue

class LegalDocumentIntegrityVerifierTest {

    @Test
    fun `configured document with matching canonical file and hash verifies`() {
        verifier(
            document(
                version = "2026-07-01-test",
                contentSha256 = "78829bddbdbf5f73c35af82b61cc1ae3c81ecac78853a18e007450c0e1a858f3"
            )
        ).verifyCurrentDocuments()
    }

    @Test
    fun `missing canonical file fails verification`() {
        val exception = assertThrows<IllegalStateException> {
            verifier(
                document(
                    version = "missing-test",
                    contentSha256 = "a".repeat(64)
                )
            ).verifyCurrentDocuments()
        }

        assertTrue(exception.message?.contains("TERMS_OF_USE") == true)
        assertTrue(exception.message?.contains("missing-test") == true)
        assertTrue(exception.message?.contains("legal-documents/terms/missing-test/document.html") == true)
    }

    @Test
    fun `hash mismatch fails verification`() {
        val exception = assertThrows<IllegalStateException> {
            verifier(
                document(
                    version = "2026-07-01-test",
                    contentSha256 = "0".repeat(64)
                )
            ).verifyCurrentDocuments()
        }

        assertTrue(exception.message?.contains("TERMS_OF_USE") == true)
        assertTrue(exception.message?.contains("2026-07-01-test") == true)
        assertTrue(exception.message?.contains("legal-documents/terms/2026-07-01-test/document.html") == true)
        assertTrue(exception.message?.contains("expectedSha256=${"0".repeat(64)}") == true)
        assertTrue(
            exception.message?.contains(
                "actualSha256=78829bddbdbf5f73c35af82b61cc1ae3c81ecac78853a18e007450c0e1a858f3"
            ) == true
        )
    }

    @Test
    fun `empty catalog verification is no-op`() {
        LegalDocumentIntegrityVerifier(
            properties = LegalDocumentProperties(),
            resourceLoader = DefaultResourceLoader()
        ).verifyCurrentDocuments()
    }

    private fun verifier(document: LegalDocumentDefinition): LegalDocumentIntegrityVerifier =
        LegalDocumentIntegrityVerifier(
            properties = LegalDocumentProperties(documents = listOf(document)),
            resourceLoader = DefaultResourceLoader()
        )

    private fun document(
        version: String,
        contentSha256: String
    ): LegalDocumentDefinition =
        LegalDocumentDefinition(
            type = LegalDocumentType.TERMS_OF_USE,
            version = version,
            url = "https://example.test/terms",
            contentSha256 = contentSha256,
            requiredAction = LegalDocumentAction.ACCEPTED
        )
}
