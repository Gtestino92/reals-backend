package com.reals.backend.config.legal

import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "legal")
data class LegalDocumentProperties(
    val documents: List<LegalDocumentDefinition> = emptyList()
) {
    init {
        val duplicateType = documents
            .groupBy { it.type }
            .filterValues { it.size > 1 }
            .keys
            .firstOrNull()

        require(duplicateType == null) {
            "Duplicate legal document type configured: $duplicateType"
        }

        documents.forEach { document ->
            require(document.version.isNotBlank()) {
                "Legal document version must not be blank for ${document.type}"
            }
            require(document.url.isNotBlank()) {
                "Legal document URL must not be blank for ${document.type}"
            }
        }
    }

    fun currentDocuments(): List<LegalDocumentDefinition> =
        documents.sortedBy { it.type.ordinal }
}

data class LegalDocumentDefinition(
    val type: LegalDocumentType,
    val version: String,
    val url: String,
    val requiredAction: LegalDocumentAction
)
