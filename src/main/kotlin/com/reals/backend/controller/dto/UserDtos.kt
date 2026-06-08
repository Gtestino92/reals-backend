package com.reals.backend.controller.dto

import com.reals.backend.domain.User
import com.reals.backend.domain.UserStatus
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class DevCreateUserRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 255)
    val email: String
)

data class UserResponse(
    val id: UUID,
    val email: String?,
    val status: UserStatus,
    val deletedAt: OffsetDateTime?,
    val deletionFinalizesAt: OffsetDateTime?,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(user: User) = UserResponse(
            id = user.id,
            email = user.email,
            status = user.status,
            deletedAt = user.deletedAt,
            deletionFinalizesAt = user.deletionFinalizesAt,
            createdAt = user.createdAt
        )
    }
}
