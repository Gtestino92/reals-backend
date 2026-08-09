package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "user_home_status")
data class UserHomeStatus(

    @Id
    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "dirty", nullable = false)
    var dirty: Boolean = false,

    @Column(name = "next_refresh_at")
    var nextRefreshAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
