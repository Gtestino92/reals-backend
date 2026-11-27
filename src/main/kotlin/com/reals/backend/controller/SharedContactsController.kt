package com.reals.backend.controller

import com.reals.backend.config.SharedContactsProperties
import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.service.LegalComplianceService
import com.reals.backend.service.contacts.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.DeserializationFeature
import java.util.UUID

@RestController
@RequestMapping("/api/me/shared-contacts")
class SharedContactsController(
    private val participation: ContactParticipationService,
    private val legal: LegalComplianceService,
    private val settings: SharedContactsProperties,
    mapper: ObjectMapper
) {
    private val reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    @GetMapping
    fun status(@CurrentUserId owner: UUID) = participation.status(owner)

    class ConsentRequest(val version: Int)
    @PutMapping("/consent")
    fun consent(@CurrentUserId owner: UUID, @RequestHeader("X-Contact-Revision", required = false) revision: UUID?, @RequestBody acceptance: ConsentRequest): ContactStatus {
        legal.requireCurrentRequirementsSatisfied(owner)
        return participation.accept(owner, acceptance.version, requiredRevision(revision))
    }

    @PutMapping("/selection", consumes = ["application/json"])
    fun replace(@CurrentUserId owner: UUID, @RequestHeader("X-Contact-Revision", required = false) revision: UUID?, request: HttpServletRequest): ContactImportResult {
        return importSelection(owner, revision, request, participation::replace)
    }

    @PutMapping("/selection/additions", consumes = ["application/json"])
    fun add(@CurrentUserId owner: UUID, @RequestHeader("X-Contact-Revision", required = false) revision: UUID?, request: HttpServletRequest): ContactAdditionResult =
        importSelection(owner, revision, request, participation::add)

    private fun <T> importSelection(owner: UUID, revision: UUID?, request: HttpServletRequest,
                                    apply: (UUID, ContactSelection, UUID) -> T): T {
        legal.requireCurrentRequirementsSatisfied(owner)
        val expected = requiredRevision(revision)
        participation.requireImportConsent(owner, expected)
        val bytes = request.inputStream.readNBytes(settings.maxBodyBytes + 1)
        if (bytes.size > settings.maxBodyBytes) throw ContactRequestError(413, "CONTACT_BODY_LIMIT", "Reduce the selection size")
        val root = try { reader.readTree(bytes) } catch (_: tools.jackson.core.JacksonException) { malformed() }
        if (root == null || !root.isObject || root.propertyNames().any { it !in setOf("numbers", "defaultRegion") }) malformed()
        val numbers = root.path("numbers")
        val region = root.path("defaultRegion")
        if (!numbers.isArray || !(region.isMissingNode || region.isNull || region.isString)) malformed()
        if (numbers.size() > settings.maxRaw) throw ContactRequestError(413, "CONTACT_INPUT_LIMIT", "Select fewer entries")
        val selected = numbers.values().map {
            if (!it.isString) malformed()
            it.asString()
        }
        val defaultRegion = if (region.isString) region.asString() else null
        if (defaultRegion != null && defaultRegion.length != 2) malformed()
        return apply(owner, ContactSelection(selected, defaultRegion), expected)
    }

    @DeleteMapping("/selection")
    fun clear(@CurrentUserId owner: UUID) = remove(owner, false)

    @DeleteMapping("/consent")
    fun withdraw(@CurrentUserId owner: UUID) = remove(owner, true)

    private fun remove(owner: UUID, withdraw: Boolean): ResponseEntity<Void> {
        participation.remove(owner, withdraw)
        return ResponseEntity.noContent().build()
    }

    private fun requiredRevision(value: UUID?): UUID = value ?: throw ContactRequestError(
        428, "CONTACT_REVISION_REQUIRED", "Read shared-contact status and send X-Contact-Revision"
    )

    private fun malformed(): Nothing = throw ContactRequestError(400, "INVALID_CONTACT_SELECTION", "Provide a numbers array and optional defaultRegion")

    @ExceptionHandler(ContactRequestError::class)
    fun rejection(error: ContactRequestError) = ResponseEntity.status(error.status)
        .body(mapOf("code" to error.code, "message" to error.message))
}
