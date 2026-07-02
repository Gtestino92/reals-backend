package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.OffsetDateTime
import java.util.UUID

enum class PhotoStorageProvider {
    S3
}

enum class PhotoModerationStatus {
    PENDING,
    APPROVED,
    REJECTED,
    NEEDS_REVIEW
}

@Entity
@Table(
    name = "profile_photos",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_profile_photo_profile_position",
            columnNames = ["profile_id", "position"]
        )
    ]
)
data class ProfilePhoto(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "profile_id", nullable = false)
    var profileId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_provider", nullable = false)
    var storageProvider: PhotoStorageProvider = PhotoStorageProvider.S3,

    @Column(name = "storage_bucket")
    var storageBucket: String? = null,

    @Column(name = "storage_key", nullable = false)
    var storageKey: String,

    @Column(name = "position", nullable = false)
    var position: Int,

    @Column(name = "is_person_photo", nullable = false)
    var isPersonPhoto: Boolean = false,

    @Column(name = "is_full_body", nullable = false)
    var isFullBody: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false)
    var validationStatus: PhotoValidationStatus = PhotoValidationStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false)
    var moderationStatus: PhotoModerationStatus = PhotoModerationStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)

enum class PhotoValidationStatus {
    PENDING,
    VALIDATED,
    FAILED
}
