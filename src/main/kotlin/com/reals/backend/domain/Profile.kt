package com.reals.backend.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*

enum class Gender {
    MALE,
    FEMALE,
    NON_BINARY,
    OTHER
}

enum class LookingForGender {
    MEN,
    WOMEN,
    EVERYONE,
    OTHER
}

enum class Intention {
    DATE,
    FRIENDSHIP,
    CASUAL
}

enum class ProfileStatus {
    DRAFT,
    ACTIVE,
    INACTIVE
}

@Entity
@Table(name = "profiles")
data class Profile(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "display_name", nullable = false)
    var displayName: String,

    @Column(name = "birth_date", nullable = false)
    var birthDate: LocalDate,

    @Column(name = "identity_verified", nullable = false)
    var identityVerified: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false)
    var gender: Gender,

    @Enumerated(EnumType.STRING)
    @Column(name = "looking_for_gender", nullable = false)
    var lookingForGender: LookingForGender,

    @Enumerated(EnumType.STRING)
    @Column(name = "intention", nullable = false)
    var intention: Intention,

    @Column(name = "city", nullable = false)
    var city: String,

    @Column(name = "country", nullable = false)
    var country: String,

    @Column(name = "bio")
    var bio: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ProfileStatus = ProfileStatus.DRAFT,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
