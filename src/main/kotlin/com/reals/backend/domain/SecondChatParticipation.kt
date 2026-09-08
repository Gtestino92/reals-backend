package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.OffsetDateTime
import java.util.UUID

enum class SecondChatAttendanceStatus {
    PENDING,
    ON_TIME,
    LATE,
    NO_SHOW
}

@Entity
@Table(
    name = "second_chat_participations",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_second_chat_participation_connection_user",
            columnNames = ["connection_id", "user_id"]
        )
    ],
    indexes = [
        Index(name = "idx_second_chat_participation_connection", columnList = "connection_id"),
        Index(name = "idx_second_chat_participation_user", columnList = "user_id"),
        Index(name = "idx_second_chat_participation_status", columnList = "attendance_status")
    ]
)
data class SecondChatParticipation(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "connection_id", nullable = false)
    var connectionId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_status", nullable = false)
    var attendanceStatus: SecondChatAttendanceStatus = SecondChatAttendanceStatus.PENDING,

    @Column(name = "joined_at")
    var joinedAt: OffsetDateTime? = null,

    @Column(name = "resolved_at")
    var resolvedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
