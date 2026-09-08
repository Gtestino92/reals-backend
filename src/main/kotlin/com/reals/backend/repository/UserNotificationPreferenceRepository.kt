package com.reals.backend.repository

import com.reals.backend.domain.NotificationPreferenceCategory
import com.reals.backend.domain.UserNotificationPreference
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserNotificationPreferenceRepository : JpaRepository<UserNotificationPreference, UUID> {

    fun findByUserId(userId: UUID): List<UserNotificationPreference>

    fun findByUserIdAndCategoryIn(
        userId: UUID,
        categories: Collection<NotificationPreferenceCategory>
    ): List<UserNotificationPreference>

    fun countByUserIdAndCategory(
        userId: UUID,
        category: NotificationPreferenceCategory
    ): Long

    fun findByUserIdAndCategory(
        userId: UUID,
        category: NotificationPreferenceCategory
    ): UserNotificationPreference?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from UserNotificationPreference p where p.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int
}
