package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "profile_photos")
data class ProfilePhoto(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "profile_id", nullable = false)
    val profileId: UUID,

    @Column(name = "url", nullable = false)
    var url: String,

    @Column(name = "position", nullable = false)
    val position: Int,

    @Column(name = "is_person_photo", nullable = false)
    var isPersonPhoto: Boolean = false,

    @Column(name = "is_full_body", nullable = false)
    var isFullBody: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
