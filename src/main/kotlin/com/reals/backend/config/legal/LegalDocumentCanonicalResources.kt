package com.reals.backend.config.legal

import com.reals.backend.domain.LegalDocumentType

object LegalDocumentCanonicalResources {
    fun directorySlug(type: LegalDocumentType): String =
        when (type) {
            LegalDocumentType.TERMS_OF_USE -> "terms"
            LegalDocumentType.PRIVACY_NOTICE -> "privacy"
            LegalDocumentType.COMMUNITY_GUIDELINES -> "community-guidelines"
        }

    fun resourcePath(document: LegalDocumentDefinition): String =
        resourcePath(
            type = document.type,
            version = document.version
        )

    fun resourcePath(
        type: LegalDocumentType,
        version: String
    ): String =
        "legal-documents/${directorySlug(type)}/$version/document.html"
}
