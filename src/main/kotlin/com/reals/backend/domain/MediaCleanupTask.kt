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

enum class MediaCleanupOperation {
    DELETE_OBJECT
}

enum class MediaCleanupTaskStatus {
    PENDING,
    PROCESSING,
    FAILED
}

@Entity
@Table(
    name = "media_cleanup_tasks",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_media_cleanup_tasks_delete_object",
            columnNames = ["operation", "storage_provider", "bucket", "object_key"]
        )
    ]
)
data class MediaCleanupTask(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false)
    var operation: MediaCleanupOperation = MediaCleanupOperation.DELETE_OBJECT,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: MediaCleanupTaskStatus = MediaCleanupTaskStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_provider", nullable = false)
    var storageProvider: PhotoStorageProvider = PhotoStorageProvider.S3,

    @Column(name = "bucket", nullable = false)
    var bucket: String,

    @Column(name = "object_key", nullable = false)
    var objectKey: String,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: OffsetDateTime,

    @Column(name = "lease_until")
    var leaseUntil: OffsetDateTime? = null,

    @Column(name = "last_error")
    var lastError: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
