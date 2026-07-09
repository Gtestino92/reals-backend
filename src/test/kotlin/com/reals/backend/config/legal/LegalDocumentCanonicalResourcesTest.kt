package com.reals.backend.config.legal

import com.reals.backend.domain.LegalDocumentType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LegalDocumentCanonicalResourcesTest {

    @Test
    fun `maps legal document types to canonical directory slugs`() {
        assertEquals("terms", LegalDocumentCanonicalResources.directorySlug(LegalDocumentType.TERMS_OF_USE))
        assertEquals("privacy", LegalDocumentCanonicalResources.directorySlug(LegalDocumentType.PRIVACY_NOTICE))
        assertEquals(
            "community-guidelines",
            LegalDocumentCanonicalResources.directorySlug(LegalDocumentType.COMMUNITY_GUIDELINES)
        )
    }

    @Test
    fun `derives canonical classpath resource path`() {
        assertEquals(
            "legal-documents/terms/2026-07-01/document.html",
            LegalDocumentCanonicalResources.resourcePath(
                type = LegalDocumentType.TERMS_OF_USE,
                version = "2026-07-01"
            )
        )
        assertEquals(
            "legal-documents/privacy/2026-07-01/document.html",
            LegalDocumentCanonicalResources.resourcePath(
                type = LegalDocumentType.PRIVACY_NOTICE,
                version = "2026-07-01"
            )
        )
        assertEquals(
            "legal-documents/community-guidelines/2026-07-01/document.html",
            LegalDocumentCanonicalResources.resourcePath(
                type = LegalDocumentType.COMMUNITY_GUIDELINES,
                version = "2026-07-01"
            )
        )
    }
}
