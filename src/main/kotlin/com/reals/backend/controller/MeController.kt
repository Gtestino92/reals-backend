package com.reals.backend.controller

import com.reals.backend.config.CurrentUserId
import com.reals.backend.controller.dto.UserResponse
import com.reals.backend.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class MeController(
    private val userService: UserService
) {

    @GetMapping("/api/me")
    fun getMe(
        @CurrentUserId userId: UUID
    ): ResponseEntity<UserResponse> {
        val user = userService.findByIdOrThrow(
            userId = userId
        )
        return ResponseEntity.ok(
            UserResponse.from(user)
        )
    }
}
