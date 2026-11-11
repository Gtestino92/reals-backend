package com.reals.backend.adminauth

import com.reals.backend.config.security.authentication.FirebaseSignInProvider
import com.reals.backend.domain.*
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.AuditEventService
import com.reals.backend.service.PenaltyService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Verified token/App Check context only; never constructed from client identity headers. */
data class AdminRequestIdentity(val userId: UUID, val uid: String, val email: String?,
    val emailVerified: Boolean, val provider: FirebaseSignInProvider?, val authTime: Long, val appId: String)

data class AdminGrantResponse(val grant: String, val expiresAt: Instant)
data class AdminResult(val value: AdminGrantResponse? = null, val error: String? = null, val retryAfter: Long? = null)

@Service
class AdminAccessService(
    private val properties: AdminMfaProperties,
    private val identities: AdminIdentityProperties,
    private val users: UserRepository,
    private val states: AdminMfaStateRepository,
    private val grants: AdminAccessGrantRepository,
    private val penalties: PenaltyService,
    private val audit: AuditEventService,
    private val clock: Clock
) {
    fun configured() = properties.factorId != null && identities.uids.isNotEmpty() && identities.emails.isNotEmpty() && identities.webAppIds.isNotEmpty()

    private fun eligible(identity: AdminRequestIdentity, user: User?): Boolean =
        user != null && user.status == UserStatus.ACTIVE && user.firebaseUid == identity.uid &&
            identity.uid in identities.uids && identity.emailVerified &&
            identity.email?.trim()?.lowercase(java.util.Locale.ROOT) in identities.emails &&
            identity.provider == FirebaseSignInProvider.GOOGLE && identity.appId in identities.webAppIds &&
            identity.authTime > 0 && penalties.resolveEffectiveBan(user.id, java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)) == null

    @Transactional(readOnly = true)
    fun eligible(identity: AdminRequestIdentity): Boolean = eligible(identity, users.findById(identity.userId).orElse(null))

    // Global order: user -> MFA state -> grants. Every mutation of grants takes the user lock.
    @Transactional
    fun verify(identity: AdminRequestIdentity, code: String): AdminResult {
        val user = users.findAllByIdForUpdate(listOf(identity.userId)).singleOrNull()
        if (!eligible(identity, user)) return AdminResult(error = "ADMIN_ACCESS_DENIED")
        if (!configured()) return AdminResult(error = "ADMIN_MFA_NOT_CONFIGURED")
        val now = clock.instant()
        val auth = Instant.ofEpochSecond(identity.authTime)
        if (auth > now || !now.isBefore(auth.plusSeconds(properties.maxAuthAgeSeconds))) return AdminResult(error = "ADMIN_ACCESS_DENIED")
        val factor = properties.factorId!!
        val state = states.findById(identity.userId).orElseGet {
            states.save(AdminMfaState(userId = identity.userId, factorId = factor))
        }
        if (state.factorId != factor) {
            state.factorId = factor
            // Preserve failures/lockout and forbid codes across the rotation boundary.
            state.lastAcceptedStep = maxOf(state.lastAcceptedStep, now.epochSecond / 30 + 1)
            event(AuditEventType.ADMIN_MFA_FACTOR_CHANGED, identity)
        }
        state.updatedAt = now
        state.lockedUntil?.let { until ->
            if (now < until) return AdminResult(error = "ADMIN_MFA_RATE_LIMITED", retryAfter = secondsUntil(now, until))
            state.lockedUntil = null
            state.failedAttempts = 0
            state.failureWindowStartedAt = null
        }
        if (state.failureWindowStartedAt == null || now >= state.failureWindowStartedAt!!.plusSeconds(properties.failureWindowSeconds)) {
            state.failedAttempts = 0
            state.failureWindowStartedAt = now
        }
        val accepted = AdminTotp(properties.key!!).acceptedStep(code, now, state.lastAcceptedStep)
        if (accepted == null) {
            state.failedAttempts++
            if (state.failedAttempts >= properties.maxFailedAttempts) {
                state.lockedUntil = now.plusSeconds(properties.lockoutSeconds)
                event(AuditEventType.ADMIN_MFA_LOCKED, identity)
                return AdminResult(error = "ADMIN_MFA_RATE_LIMITED", retryAfter = properties.lockoutSeconds)
            }
            // One failure event per window, plus the threshold event, bounds audit volume.
            if (state.failedAttempts == 1) event(AuditEventType.ADMIN_MFA_FAILED, identity)
            return AdminResult(error = "ADMIN_MFA_INVALID_CODE")
        }
        state.lastAcceptedStep = accepted
        state.failedAttempts = 0
        state.failureWindowStartedAt = null
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val expires = now.plusSeconds(properties.grantTtlSeconds)
        grants.save(AdminAccessGrant(sha256(token), identity.userId, identity.uid, identity.authTime,
            "GOOGLE", identity.appId, factor, now, expires))
        event(AuditEventType.ADMIN_MFA_VERIFIED, identity)
        event(AuditEventType.ADMIN_GRANT_ISSUED, identity)
        return AdminResult(value = AdminGrantResponse(token, expires))
    }

    @Transactional(readOnly = true)
    fun grantError(identity: AdminRequestIdentity, token: String?): String? {
        if (!eligible(identity)) return "ADMIN_ACCESS_DENIED"
        if (!configured()) return "ADMIN_MFA_NOT_CONFIGURED"
        if (token == null || !Regex("[A-Za-z0-9_-]{43}").matches(token)) return "ADMIN_MFA_REQUIRED"
        val grant = grants.findById(sha256(token)).orElse(null) ?: return "ADMIN_MFA_REQUIRED"
        if (!matches(grant, identity)) return "ADMIN_ACCESS_DENIED"
        if (grant.revokedAt != null) return "ADMIN_GRANT_REVOKED"
        if (clock.instant() >= grant.expiresAt) return "ADMIN_GRANT_EXPIRED"
        return null
    }

    private fun matches(grant: AdminAccessGrant, identity: AdminRequestIdentity) =
        grant.userId == identity.userId && grant.firebaseUid == identity.uid && grant.authTime == identity.authTime &&
            grant.provider == "GOOGLE" && grant.webAppId == identity.appId && grant.factorId == properties.factorId

    @Transactional
    fun revoke(identity: AdminRequestIdentity, token: String?) {
        val user = users.findAllByIdForUpdate(listOf(identity.userId)).singleOrNull()
        if (!eligible(identity, user) || token == null || !Regex("[A-Za-z0-9_-]{43}").matches(token)) return
        val grant = grants.findById(sha256(token)).orElse(null) ?: return
        val now = clock.instant()
        // Expiry already wins when due; revocation must not replace that terminal outcome.
        if (matches(grant, identity) && grant.revokedAt == null && now < grant.expiresAt) {
            grant.revokedAt = now
            event(AuditEventType.ADMIN_GRANT_REVOKED, identity)
        }
    }

    private fun event(type: AuditEventType, identity: AdminRequestIdentity) {
        audit.record(type, AuditAggregateType.USER, identity.userId, actorUserId = identity.userId)
    }
    private fun secondsUntil(now: Instant, until: Instant) = maxOf(1, (java.time.Duration.between(now, until).toMillis() + 999) / 1000)
}
