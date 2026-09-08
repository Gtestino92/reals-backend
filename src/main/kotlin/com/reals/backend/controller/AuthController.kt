package com.reals.backend.controller

import com.reals.backend.controller.dto.PasswordResetRequest
import com.reals.backend.service.PasswordResetService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthController(
    private val passwordResetService: PasswordResetService
) {

    @PostMapping("/api/auth/password-reset")
    fun requestPasswordReset(
        @Valid @RequestBody request: PasswordResetRequest
    ): ResponseEntity<Void> {
        passwordResetService.requestPasswordReset(request.email)
        return ResponseEntity.accepted().build()
    }
}

