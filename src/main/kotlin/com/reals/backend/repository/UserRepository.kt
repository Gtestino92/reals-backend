package com.reals.backend.repository

import com.reals.backend.domain.User
import com.reals.backend.domain.UserStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun existsByEmail(email: String): Boolean
    fun findByEmail(email: String): User?
    fun findByFirebaseUid(firebaseUid: String): User?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
    UPDATE User u
    SET u.status = :deletedStatus,
        u.deletedAt = :deletedAt,
        u.updatedAt = :updatedAt,
        u.email = :deletedEmail
    WHERE u.id = :userId
      AND u.status = :activeStatus
    """
    )
    fun softDeleteActiveById(
        @Param("userId") userId: UUID,
        @Param("deletedEmail") deletedEmail: String,
        @Param("deletedStatus") deletedStatus: UserStatus = UserStatus.DELETED,
        @Param("activeStatus") activeStatus: UserStatus = UserStatus.ACTIVE,
        @Param("deletedAt") deletedAt: OffsetDateTime,
        @Param("updatedAt") updatedAt: OffsetDateTime = deletedAt,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id in :ids order by u.id")
    fun findAllByIdForUpdate(
        @Param("ids") ids: Collection<UUID>
    ): List<User>
}
