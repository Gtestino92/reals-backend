package com.reals.backend.adminauth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class AdminVerifyRequest(val code: String)
data class AdminStatusResponse(val configured: Boolean, val grantValid: Boolean)

@RestController
@RequestMapping("/api/admin-auth")
class AdminAuthController(private val service: AdminAccessService) {
    @GetMapping("/status")
    fun status(request: HttpServletRequest): ResponseEntity<*> {
        val identity = identity(request) ?: return error("ADMIN_ACCESS_DENIED")
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
            AdminStatusResponse(service.configured(), service.grantError(identity, grant(request)) == null))
    }
    @PostMapping("/verify")
    fun verify(@RequestBody body: AdminVerifyRequest, request: HttpServletRequest): ResponseEntity<*> {
        val identity = identity(request) ?: return error("ADMIN_ACCESS_DENIED")
        // Commit failure accounting before mapping the rejection to an HTTP error.
        val result = service.verify(identity, body.code)
        return if (result.error != null) error(result.error, result.retryAfter)
            else ResponseEntity.ok().header("Cache-Control", "no-store").body(result.value)
    }
    @PostMapping("/revoke")
    fun revoke(request: HttpServletRequest): ResponseEntity<*> {
        val identity = identity(request) ?: return error("ADMIN_ACCESS_DENIED")
        service.revoke(identity, grant(request))
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build<Void>()
    }
    private fun identity(request: HttpServletRequest) = request.getAttribute("reals.admin.identity") as? AdminRequestIdentity
    private fun grant(request: HttpServletRequest) = request.getHeader("X-Reals-Admin-Grant")
    private fun error(code: String, retryAfter: Long? = null): ResponseEntity<*> {
        val status = when (code) { "ADMIN_MFA_INVALID_CODE" -> 400; "ADMIN_MFA_RATE_LIMITED" -> 429; else -> 403 }
        val builder = ResponseEntity.status(status).header("Cache-Control", "no-store")
        if (retryAfter != null) builder.header("Retry-After", retryAfter.toString())
        return builder.body(mapOf("code" to code, "error" to org.springframework.http.HttpStatus.valueOf(status).reasonPhrase,
            "message" to "Administrative authentication could not be completed"))
    }
}
