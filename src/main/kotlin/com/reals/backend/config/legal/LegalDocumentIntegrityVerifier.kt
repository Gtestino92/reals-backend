package com.reals.backend.config.legal

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

@Component
class LegalDocumentIntegrityVerifier(
    private val properties: LegalDocumentProperties,
    private val resourceLoader: ResourceLoader
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        verifyCurrentDocuments()
    }

    fun verifyCurrentDocuments() {
        properties.currentDocuments().forEach { document ->
            verify(document)
        }
    }

    private fun verify(document: LegalDocumentDefinition) {
        val resourcePath = LegalDocumentCanonicalResources.resourcePath(document)
        val resource = resourceLoader.getResource("classpath:$resourcePath")

        if (!resource.exists()) {
            throw IllegalStateException(
                "Missing canonical legal document resource for type=${document.type}, " +
                    "version=${document.version}, expectedResource=$resourcePath"
            )
        }

        val bytes = resource.inputStream.use { it.readBytes() }
        val actualSha256 = LegalDocumentSha256.hash(bytes)

        if (actualSha256 != document.contentSha256) {
            throw IllegalStateException(
                "Canonical legal document SHA-256 mismatch for type=${document.type}, " +
                    "version=${document.version}, resource=$resourcePath, " +
                    "expectedSha256=${document.contentSha256}, actualSha256=$actualSha256"
            )
        }
    }
}
