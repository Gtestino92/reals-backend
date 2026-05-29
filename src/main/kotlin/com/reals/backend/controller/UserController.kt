package com.reals.backend.controller

import com.reals.backend.controller.dto.CreateUserRequest
import com.reals.backend.controller.dto.UserResponse
import com.reals.backend.service.UserService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService
) {

    @PostMapping
    fun createUser(
        @Valid
        @RequestBody request: CreateUserRequest
    ): ResponseEntity<UserResponse> {
        val user = userService.createUser(
            email = request.email
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(
            UserResponse.from(user)
        )
    }

    @GetMapping("/{userId}")
    fun getUser(
        @PathVariable userId: UUID
    ): ResponseEntity<UserResponse> {
        val user = userService.findByIdOrThrow(
            userId = userId
        )
        return ResponseEntity.ok(
            UserResponse.from(user)
        )
    }
}
