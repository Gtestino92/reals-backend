package com.reals.backend.config.legal

import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "legal")
data class LegalDocumentProperties(
    val documents: List<LegalDocumentDefinition> = emptyList()
) {
    private val contentSha256Pattern = Regex("^[0-9a-f]{64}$")

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
            require(!document.version.contains("/") && !document.version.contains("\\") && !document.version.contains("..")) {
                "Legal document version contains unsafe path characters for ${document.type}: ${document.version}"
            }
            require(document.url.isNotBlank()) {
                "Legal document URL must not be blank for ${document.type}"
            }
            require(document.contentSha256.isNotBlank()) {
                "Legal document contentSha256 must not be blank for ${document.type}"
            }
            require(contentSha256Pattern.matches(document.contentSha256)) {
                "Legal document contentSha256 must be exactly 64 lowercase hexadecimal characters for ${document.type}"
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
    val contentSha256: String,
    val requiredAction: LegalDocumentAction
)
