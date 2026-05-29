package com.reals.backend.controller.dto

import com.reals.backend.domain.User
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class CreateUserRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 255)
    val email: String
)

data class UserResponse(
    val id: UUID,
    val email: String?,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(user: User) = UserResponse(
            id = user.id,
            email = user.email,
            createdAt = user.createdAt
        )
    }
}
