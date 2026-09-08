package com.reals.backend.repository

import com.reals.backend.domain.Profile
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProfileRepository : JpaRepository<Profile, UUID> {
    fun findByUserId(userId: UUID): Profile?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Profile p where p.userId = :userId")
    fun findByUserIdForUpdate(
        @Param("userId") userId: UUID
    ): Profile?

    fun findByUserIdIn(userIds: Collection<UUID>): List<Profile>
}
