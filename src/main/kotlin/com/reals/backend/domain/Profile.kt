package com.reals.backend.domain

import jakarta.persistence.*
import org.hibernate.annotations.ColumnDefault
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*

enum class Gender {
    MALE,
    FEMALE,
    NON_BINARY,
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

enum class ProfileAuthenticityVerificationStatus {
    NOT_STARTED,
    PENDING,
    VERIFIED,
    REJECTED,
    NEEDS_REVIEW,
    STALE
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

    @Column(name = "authenticity_verified", nullable = false)
    var authenticityVerified: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "authenticity_verification_status", nullable = false)
    var authenticityVerificationStatus: ProfileAuthenticityVerificationStatus =
        ProfileAuthenticityVerificationStatus.NOT_STARTED,

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false)
    var gender: Gender,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "profile_looking_for_genders",
        joinColumns = [JoinColumn(name = "profile_id")]
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false)
    var lookingForGenders: MutableSet<Gender> = mutableSetOf(),

    @Enumerated(EnumType.STRING)
    @Column(name = "intention", nullable = false)
    var intention: Intention,

    @Column(name = "city", nullable = false)
    var city: String,

    @Column(name = "country", nullable = false)
    var country: String,

    @Column(name = "bio")
    var bio: String? = null,

    @ColumnDefault("18")
    @Column(name = "preferred_min_age", nullable = false)
    var preferredMinAge: Int = 18,

    @ColumnDefault("99")
    @Column(name = "preferred_max_age", nullable = false)
    var preferredMaxAge: Int = 99,

    @ColumnDefault("50")
    @Column(name = "max_distance_km", nullable = false)
    var maxDistanceKm: Int = 50,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ProfileStatus = ProfileStatus.DRAFT,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
