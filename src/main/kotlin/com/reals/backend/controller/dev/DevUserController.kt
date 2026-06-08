package com.reals.backend.controller.dev

import com.reals.backend.controller.dto.DevCreateUserRequest
import com.reals.backend.controller.dto.UserResponse
import com.reals.backend.service.UserService
import jakarta.validation.Valid
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("local", "local-nodb", "local-postgres")
@RequestMapping("/api/local-dev/users")
class DevUserController(
    private val userService: UserService
) {

    @PostMapping
    fun createUser(
        @Valid
        @RequestBody request: DevCreateUserRequest
    ): ResponseEntity<UserResponse> {
        val user = userService.createUser(
            email = request.email
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(
            UserResponse.from(user)
        )
    }
}
