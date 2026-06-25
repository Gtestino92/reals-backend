package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

enum class PushPlatform {
    ANDROID
}

@Entity
@Table(
    name = "push_device_tokens",
    indexes = [
        Index(name = "idx_push_device_tokens_user_id", columnList = "user_id")
    ]
)
data class PushDeviceToken(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "token", nullable = false, unique = true, length = 4096)
    var token: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    var platform: PushPlatform,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: OffsetDateTime = OffsetDateTime.now()
)
