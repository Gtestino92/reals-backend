package com.reals.backend.repository

import com.reals.backend.domain.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun existsByEmail(email: String): Boolean
    fun findByEmail(email: String): User?
    fun findByFirebaseUid(firebaseUid: String): User?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id in :ids order by u.id")
    fun findAllByIdForUpdate(
        @Param("ids") ids: Collection<UUID>
    ): List<User>
}
