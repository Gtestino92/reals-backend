package com.reals.backend.controller.dto

import com.reals.backend.domain.PushPlatform
import jakarta.validation.constraints.NotBlank

data class RegisterPushTokenRequest(
    @field:NotBlank
    val token: String,
    val platform: PushPlatform
)

data class RegisterPushTokenResponse(
    val registered: Boolean
)
