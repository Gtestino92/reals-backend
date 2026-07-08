package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.CurrentLegalDocumentResponse
import com.reals.backend.controller.dto.CurrentLegalDocumentsResponse
import com.reals.backend.controller.dto.LegalDocumentActionResponse
import com.reals.backend.controller.dto.LegalStatusResponse
import com.reals.backend.controller.dto.RecordLegalDocumentActionRequest
import com.reals.backend.service.LegalDocumentService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class LegalDocumentController(
    private val legalDocumentService: LegalDocumentService
) {

    @GetMapping("/api/legal/documents/current")
    fun currentDocuments(): ResponseEntity<CurrentLegalDocumentsResponse> =
        ResponseEntity.ok(
            CurrentLegalDocumentsResponse(
                documents = legalDocumentService.currentDocuments()
                    .map { CurrentLegalDocumentResponse.from(it) }
            )
        )

    @GetMapping("/api/me/legal-status")
    fun legalStatus(
        @CurrentUserId userId: UUID
    ): ResponseEntity<LegalStatusResponse> =
        ResponseEntity.ok(
            LegalStatusResponse.from(
                legalDocumentService.getStatus(userId = userId)
            )
        )

    @PostMapping("/api/me/legal-document-actions")
    fun recordAction(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: RecordLegalDocumentActionRequest
    ): ResponseEntity<LegalDocumentActionResponse> {
        val result = legalDocumentService.recordAction(
            userId = userId,
            documentType = request.documentType,
            documentVersion = request.documentVersion,
            action = request.action
        )

        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(LegalDocumentActionResponse.from(result.action))
    }
}
