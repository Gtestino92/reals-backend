package com.reals.backend.repository

import com.reals.backend.domain.UserHomeStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface UserHomeStatusRepository : JpaRepository<UserHomeStatus, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from UserHomeStatus s where s.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UserHomeStatus s
        set s.version = s.version + 1,
            s.dirty = true,
            s.updatedAt = :updatedAt
        where s.userId = :userId
        """
    )
    fun bumpVersion(
        @Param("userId") userId: UUID,
        @Param("updatedAt") updatedAt: OffsetDateTime
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UserHomeStatus s
        set s.dirty = false,
            s.updatedAt = :updatedAt
        where s.userId = :userId
        """
    )
    fun markClean(
        @Param("userId") userId: UUID,
        @Param("updatedAt") updatedAt: OffsetDateTime
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UserHomeStatus s
        set s.dirty = false,
            s.updatedAt = :updatedAt
        where s.userId = :userId
          and s.version = :expectedVersion
        """
    )
    fun markCleanIfVersionStill(
        @Param("userId") userId: UUID,
        @Param("expectedVersion") expectedVersion: Long,
        @Param("updatedAt") updatedAt: OffsetDateTime
    ): Int
}
