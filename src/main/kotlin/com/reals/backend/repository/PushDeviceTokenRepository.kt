package com.reals.backend.repository

import com.reals.backend.domain.PushDeviceToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface PushDeviceTokenRepository : JpaRepository<PushDeviceToken, UUID> {

    fun findByToken(token: String): PushDeviceToken?

    fun findByUserIdAndEnabledTrue(userId: UUID): List<PushDeviceToken>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update PushDeviceToken t
        set t.enabled = false,
            t.updatedAt = :updatedAt
        where t.token = :token
        """
    )
    fun disableByToken(
        @Param("token") token: String,
        @Param("updatedAt") updatedAt: OffsetDateTime = OffsetDateTime.now()
    ): Int
}
