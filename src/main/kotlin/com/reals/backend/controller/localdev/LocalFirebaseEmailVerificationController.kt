package com.reals.backend.controller.localdev

import com.reals.backend.config.security.currentuser.CurrentUserAuth
import com.reals.backend.config.security.currentuser.CurrentUserAuthContext
import com.reals.backend.service.localdev.LocalFirebaseEmailVerificationService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("local-firebase")
@ConditionalOnProperty(
    prefix = "local-dev.firebase",
    name = ["email-auto-verification-enabled"],
    havingValue = "true"
)
class LocalFirebaseEmailVerificationController(
    private val localFirebaseEmailVerificationService: LocalFirebaseEmailVerificationService
) {

    @PostMapping("/api/me/local-dev/email-verification")
    fun verifyEmail(
        @CurrentUserAuth authContext: CurrentUserAuthContext
    ): ResponseEntity<Void> {
        val firebaseUid = authContext.firebaseUid?.takeIf { it.isNotBlank() }
            ?: throw AccessDeniedException("Firebase-backed user is required")

        localFirebaseEmailVerificationService.verifyEmail(firebaseUid)

        return ResponseEntity.noContent().build()
    }
}
