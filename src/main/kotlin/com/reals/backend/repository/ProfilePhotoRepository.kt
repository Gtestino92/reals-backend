package com.reals.backend.repository

import com.reals.backend.domain.ProfilePhoto
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProfilePhotoRepository : JpaRepository<ProfilePhoto, UUID> {
    fun findByProfileId(profileId: UUID): List<ProfilePhoto>
    fun findByProfileIdAndPosition(profileId: UUID, position: Int): ProfilePhoto?
    fun countByProfileId(profileId: UUID): Long
    fun countByProfileIdAndIsPersonPhotoTrue(profileId: UUID): Long
    fun countByProfileIdAndIsFullBodyTrue(profileId: UUID): Long
}
