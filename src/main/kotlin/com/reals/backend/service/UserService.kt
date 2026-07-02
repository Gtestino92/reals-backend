package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.User
import com.reals.backend.domain.UserStatus
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.identity.FirebaseExternalAccountService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime
import java.util.*

@Service
@Transactional
class UserService(
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val matchmakingQueueRepository: MatchmakingQueueRepository,
    private val activeEngagementLockRepository: ActiveEngagementLockRepository,
    private val accountDeletionService: AccountDeletionService,
    private val firebaseExternalAccountService: FirebaseExternalAccountService,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    @param:Value("\${account.deletion.recovery-window-days:30}")
    private val accountDeletionRecoveryWindowDays: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        private val EMAIL_PATTERN =
            Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
    }

    fun findByIdOrThrow(userId: UUID): User {

        return userRepository.findById(userId)
            .orElseThrow {
                NoSuchElementException("User not found: $userId")
            }
    }

    fun findByFirebaseUid(firebaseUid: String): User? {
        require(firebaseUid.isNotBlank()) {
            "Firebase UID is required"
        }

        return userRepository.findByFirebaseUid(firebaseUid)
    }

    /**
     * Creates a new user with a unique email.
     * Throws IllegalArgumentException if the email is already registered.
     */
    fun createUser(email: String): User {
        val normalizedEmail = normalizeRequiredEmail(email)
        val existingByEmail = userRepository.findByEmail(normalizedEmail)

        if (existingByEmail != null) {
            if (existingByEmail.status == UserStatus.DELETED) {
                throw pendingOrFinalizedDeletionConflict(existingByEmail, OffsetDateTime.now())
            }

            check(false) {
                "Email already registered: $normalizedEmail"
            }
        }

        return userRepository.save(
            User(email = normalizedEmail)
        )
    }

    /**
     * Returns the user with the given Firebase UID, creating one if it does not exist yet.
     * This is intentionally called only from the explicit Firebase provisioning endpoint.
     */
    fun provisionFromFirebase(
        firebaseUid: String,
        email: String? = null
    ): User {
        require(firebaseUid.isNotBlank()) {
            "Firebase UID is required"
        }

        val normalizedEmail = normalizeOptionalEmail(email)

        val existingByFirebaseUid = userRepository.findByFirebaseUid(firebaseUid)
        if (existingByFirebaseUid != null) {
            if (existingByFirebaseUid.status == UserStatus.DELETED) {
                throw pendingOrFinalizedDeletionConflict(existingByFirebaseUid, OffsetDateTime.now())
            }

            return updateFirebaseUserEmailIfNeeded(
                user = existingByFirebaseUid,
                normalizedEmail = normalizedEmail
            )
        }

        if (normalizedEmail != null) {
            val existingByEmail = userRepository.findByEmail(normalizedEmail)

            if (existingByEmail != null) {
                if (existingByEmail.status == UserStatus.DELETED) {
                    throw pendingOrFinalizedDeletionConflict(existingByEmail, OffsetDateTime.now())
                }

                if (existingByEmail.firebaseUid == null) {
                    existingByEmail.firebaseUid = firebaseUid
                    existingByEmail.updatedAt = OffsetDateTime.now()
                    return userRepository.save(existingByEmail)
                }

                check(false) {
                    "Email already belongs to another Firebase user: $normalizedEmail"
                }
            }
        }

        return userRepository.save(
            User(
                firebaseUid = firebaseUid,
                email = normalizedEmail,
                status = UserStatus.ACTIVE,
            )
        )
    }

    fun deleteUser(userId: UUID) {
        val now = OffsetDateTime.now()
        val deletionFinalizesAt = now.plusDays(accountDeletionRecoveryWindowDays)
        val user = userRepository.findAllByIdForUpdate(listOf(userId)).singleOrNull()
            ?: throw IllegalStateException("Active user not found: $userId")

        check(user.status == UserStatus.ACTIVE) { "Active user not found: $userId" }

        accountDeletionService.closeActiveEngagementsForDeletedUser(
            userId = userId,
            now = now
        )

        matchmakingQueueRepository.deleteByUserId(userId)
        activeEngagementLockRepository.deleteByUserId(userId)
        moveProfileToDraft(userId = userId, now = now)

        val updatedRows = userRepository.softDeleteActiveById(
            userId = userId,
            deletedAt = now,
            deletionFinalizesAt = deletionFinalizesAt
        )

        check(updatedRows == 1) { "Active user not found: $userId" }
        auditEventService.record(
            eventType = AuditEventType.ACCOUNT_DELETION_REQUESTED,
            aggregateType = AuditAggregateType.USER,
            aggregateId = userId,
            actorUserId = userId
        )

        user.firebaseUid?.let {
            revokeExternalTokensAfterCommit(firebaseUid = it)
        }
        homeStateInvalidationService.bump(
            userId = userId,
            reason = "account_deleted"
        )
    }

    fun reactivateUser(userId: UUID): User {
        val now = OffsetDateTime.now()
        val user = userRepository.findAllByIdForUpdate(listOf(userId)).singleOrNull()
            ?: throw IllegalStateException("User not found: $userId")

        if (user.status == UserStatus.ACTIVE) {
            return user
        }

        val deletionFinalizesAt = user.deletionFinalizesAt
            ?: throw DomainConflictException(
                code = DomainErrorCode.ACCOUNT_DELETION_FINALIZED,
                message = "Account deletion is finalized"
            )

        if (!now.isBefore(deletionFinalizesAt)) {
            throw DomainConflictException(
                code = DomainErrorCode.ACCOUNT_DELETION_FINALIZED,
                message = "Account deletion is finalized"
            )
        }

        user.status = UserStatus.ACTIVE
        user.deletedAt = null
        user.deletionFinalizesAt = null
        user.updatedAt = now
        moveProfileToDraft(userId = userId, now = now)

        user.firebaseUid?.let {
            enableExternalAccountAfterCommit(firebaseUid = it)
        }

        val saved = userRepository.save(user)
        auditEventService.record(
            eventType = AuditEventType.ACCOUNT_REACTIVATED,
            aggregateType = AuditAggregateType.USER,
            aggregateId = saved.id,
            actorUserId = saved.id
        )
        homeStateInvalidationService.bump(
            userId = saved.id,
            reason = "account_reactivated"
        )
        return saved
    }

    @Transactional(readOnly = true)
    fun findRecoverableAccountDeletionCandidates(
        now: OffsetDateTime = OffsetDateTime.now()
    ): List<User> =
        userRepository.findByStatusAndDeletionFinalizesAtLessThanEqual(
            status = UserStatus.DELETED,
            deletionFinalizesAt = now
        )

    fun finalizeRecoverableAccountDeletion(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean =
        userRepository.finalizeDeletedUser(
            userId = userId,
            finalizedEmail = "deleted.$userId@deleted.reals.local",
            now = now
        ) == 1

    fun finalizeRecoverableAccountDeletions(now: OffsetDateTime = OffsetDateTime.now()): Int =
        findRecoverableAccountDeletionCandidates(now = now).count { user ->
            finalizeRecoverableAccountDeletion(userId = user.id, now = now)
        }

    fun lockActiveUserOrThrow(userId: UUID, action: String): User {
        val user = userRepository.findAllByIdForUpdate(listOf(userId)).singleOrNull()
            ?: throw DomainConflictException(
                code = DomainErrorCode.USER_NOT_FOUND,
                message = "$action: user was not found"
            )

        if (user.status != UserStatus.ACTIVE) {
            throw DomainConflictException(
                code = DomainErrorCode.USER_NOT_ACTIVE,
                message = "$action: user is not active"
            )
        }

        return user
    }

    fun lockActiveUsersOrThrow(userIds: Collection<UUID>, action: String): List<User> {
        val distinctIds = userIds.distinct()

        val users = userRepository.findAllByIdForUpdate(distinctIds)

        if (users.size != distinctIds.size) {
            throw DomainConflictException(
                code = DomainErrorCode.USER_NOT_FOUND,
                message = "$action: one or more users were not found"
            )
        }

        val inactiveUser = users.firstOrNull { it.status != UserStatus.ACTIVE }
        if (inactiveUser != null) {
            throw DomainConflictException(
                code = DomainErrorCode.USER_NOT_ACTIVE,
                message = "$action: one or more users are not active"
            )
        }

        return users
    }

    private fun updateFirebaseUserEmailIfNeeded(
        user: User,
        normalizedEmail: String?
    ): User {
        if (
            normalizedEmail == null ||
            user.email == normalizedEmail ||
            userRepository.existsByEmail(normalizedEmail)
        ) {
            return user
        }

        user.email = normalizedEmail
        user.updatedAt = OffsetDateTime.now()
        return userRepository.save(user)
    }

    private fun normalizeRequiredEmail(email: String): String {
        val normalizedEmail = email.trim().lowercase()

        require(normalizedEmail.isNotBlank()) {
            "Email is required"
        }

        validateEmail(normalizedEmail)

        return normalizedEmail
    }

    private fun normalizeOptionalEmail(email: String?): String? {
        val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return null

        validateEmail(normalizedEmail)

        return normalizedEmail
    }

    private fun validateEmail(normalizedEmail: String) {
        require(normalizedEmail.length <= 255) {
            "Email must be at most 255 characters"
        }

        require(EMAIL_PATTERN.matches(normalizedEmail)) {
            "Email format is invalid"
        }
    }

    private fun pendingOrFinalizedDeletionConflict(
        user: User,
        now: OffsetDateTime
    ): DomainConflictException {
        val deletionFinalizesAt = user.deletionFinalizesAt

        return if (deletionFinalizesAt != null && now.isBefore(deletionFinalizesAt)) {
            DomainConflictException(
                code = DomainErrorCode.ACCOUNT_PENDING_DELETION,
                message = "Account is pending deletion until $deletionFinalizesAt"
            )
        } else {
            DomainConflictException(
                code = DomainErrorCode.ACCOUNT_DELETION_FINALIZED,
                message = "Account deletion is finalized"
            )
        }
    }

    private fun moveProfileToDraft(
        userId: UUID,
        now: OffsetDateTime
    ) {
        val profile = profileRepository.findByUserId(userId) ?: return

        if (profile.status != ProfileStatus.DRAFT) {
            profile.status = ProfileStatus.DRAFT
            profile.updatedAt = now
            profileRepository.save(profile)
        }
    }

    private fun revokeExternalTokensAfterCommit(firebaseUid: String) {
        val action = {
            runCatching {
                firebaseExternalAccountService.revokeRefreshTokens(firebaseUid)
            }.onFailure {
                log.warn("Failed to revoke external tokens for Firebase UID {}", firebaseUid, it)
            }
            Unit
        }

        runAfterCommit(action)
    }

    private fun enableExternalAccountAfterCommit(firebaseUid: String) {
        val action = {
            runCatching {
                firebaseExternalAccountService.enableAccount(firebaseUid)
            }.onFailure {
                log.warn("Failed to enable external account for Firebase UID {}", firebaseUid, it)
            }
            Unit
        }

        runAfterCommit(action)
    }

    private fun runAfterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        action()
                    }
                }
            )
        } else {
            action()
        }
    }
}
