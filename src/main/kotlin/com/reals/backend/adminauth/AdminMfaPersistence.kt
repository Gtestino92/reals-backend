package com.reals.backend.adminauth

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "admin_mfa_state")
class AdminMfaState(
    @Id @Column(name = "user_id") var userId: UUID = UUID.randomUUID(),
    @Column(name = "factor_id", nullable = false) var factorId: String = "",
    @Column(name = "last_accepted_step", nullable = false) var lastAcceptedStep: Long = -1,
    @Column(name = "failed_attempts", nullable = false) var failedAttempts: Int = 0,
    @Column(name = "failure_window_started_at") var failureWindowStartedAt: Instant? = null,
    @Column(name = "locked_until") var lockedUntil: Instant? = null,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH
)

@Entity
@Table(name = "admin_access_grants")
class AdminAccessGrant(
    @Id @Column(name = "token_hash") var tokenHash: String = "",
    @Column(name = "user_id", nullable = false) var userId: UUID = UUID.randomUUID(),
    @Column(name = "firebase_uid", nullable = false) var firebaseUid: String = "",
    @Column(name = "auth_time", nullable = false) var authTime: Long = 0,
    @Column(nullable = false) var provider: String = "GOOGLE",
    @Column(name = "web_app_id", nullable = false) var webAppId: String = "",
    @Column(name = "factor_id", nullable = false) var factorId: String = "",
    @Column(name = "issued_at", nullable = false) var issuedAt: Instant = Instant.EPOCH,
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "revoked_at") var revokedAt: Instant? = null
)

interface AdminMfaStateRepository : JpaRepository<AdminMfaState, UUID>
interface AdminAccessGrantRepository : JpaRepository<AdminAccessGrant, String> {
    fun findFirst100ByExpiresAtLessThanEqualOrderByExpiresAtAscTokenHashAsc(now: Instant): List<AdminAccessGrant>

    @Modifying
    @Query("delete from AdminAccessGrant g where g.tokenHash in :hashes and g.expiresAt <= :now")
    fun deleteExpired(@Param("hashes") hashes: Collection<String>, @Param("now") now: Instant): Int
}
