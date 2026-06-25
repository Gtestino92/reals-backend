package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.RegisterPushTokenRequest
import com.reals.backend.controller.dto.RegisterPushTokenResponse
import com.reals.backend.service.PushDeviceTokenService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class MePushTokenController(
    private val pushDeviceTokenService: PushDeviceTokenService
) {

    @PutMapping("/api/me/push-tokens")
    fun registerPushToken(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: RegisterPushTokenRequest
    ): ResponseEntity<RegisterPushTokenResponse> {
        pushDeviceTokenService.registerToken(
            userId = userId,
            token = request.token,
            platform = request.platform
        )

        return ResponseEntity.ok(
            RegisterPushTokenResponse(registered = true)
        )
    }
}
