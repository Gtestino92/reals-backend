package com.reals.backend.repository

import com.reals.backend.domain.Profile
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProfileRepository : JpaRepository<Profile, UUID> {
    fun findByUserId(userId: UUID): Profile?

    fun findByUserIdIn(userIds: Collection<UUID>): List<Profile>
}
