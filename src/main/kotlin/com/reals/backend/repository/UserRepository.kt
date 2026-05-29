package com.reals.backend.repository

import com.reals.backend.domain.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun existsByEmail(email: String): Boolean
    fun findByEmail(email: String): User?
    fun findByFirebaseUid(firebaseUid: String): User?
}
