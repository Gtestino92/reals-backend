package com.reals.backend.controller.dto

import com.reals.backend.domain.User
import java.time.OffsetDateTime
import java.util.UUID

data class CreateUserRequest(
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
