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
    var id: UUID = UUID.randomUUID(),

    @Column(name = "profile_id", nullable = false)
    var profileId: UUID,

    @Column(name = "url", nullable = false)
    var url: String,

    @Column(name = "position", nullable = false)
    var position: Int,

    @Column(name = "is_person_photo", nullable = false)
    var isPersonPhoto: Boolean = false,

    @Column(name = "is_full_body", nullable = false)
    var isFullBody: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
