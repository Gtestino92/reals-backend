package com.reals.backend.repository

import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.ProfilePhoto
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProfilePhotoRepository : JpaRepository<ProfilePhoto, UUID> {
    fun findByProfileId(profileId: UUID): List<ProfilePhoto>
    fun findByProfileIdAndPosition(profileId: UUID, position: Int): ProfilePhoto?
    fun findTop100ByModerationStatusOrderByCreatedAtAsc(
        moderationStatus: PhotoModerationStatus
    ): List<ProfilePhoto>

    fun countByProfileId(profileId: UUID): Long
    fun countByProfileIdAndIsPersonPhotoTrue(profileId: UUID): Long
    fun countByProfileIdAndIsFullBodyTrue(profileId: UUID): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProfilePhoto p where p.id = :photoId")
    fun findByIdForUpdate(
        @Param("photoId") photoId: UUID
    ): ProfilePhoto?
}
