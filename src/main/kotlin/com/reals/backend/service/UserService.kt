package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.User
import com.reals.backend.domain.UserStatus
import com.reals.backend.config.security.authentication.FirebaseSignInProvider
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.identity.FirebaseExternalAccountService
import com.reals.backend.service.identity.ExternalAccountDeletionResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.*

@Service
@Transactional
class UserService(
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val accountDeletionService: AccountDeletionService,
    private val accountDeletionImmediateCleanupService: AccountDeletionImmediateCleanupService,
    private val firebaseExternalAccountService: FirebaseExternalAccountService,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${account.deletion.recovery-window-days:30}")
    private val accountDeletionRecoveryWindowDays: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

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
        val normalizedEmail = UserEmailNormalizer.normalizeRequired(email)
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun provisionFromFirebase(
        firebaseUid: String,
        email: String? = null,
        emailVerified: Boolean = false,
        signInProvider: FirebaseSignInProvider = FirebaseSignInProvider.PASSWORD
    ): User {
        require(firebaseUid.isNotBlank()) {
            "Firebase UID is required"
        }

        val normalizedEmail = UserEmailNormalizer.normalizeOptional(email)

        return try {
            provisionFromFirebaseInNewTransaction(firebaseUid, normalizedEmail, emailVerified, signInProvider)
        } catch (ex: DataIntegrityViolationException) {
            provisionFromFirebaseInNewTransaction(firebaseUid, normalizedEmail, emailVerified, signInProvider)
        } catch (ex: OptimisticLockingFailureException) {
            provisionFromFirebaseInNewTransaction(firebaseUid, normalizedEmail, emailVerified, signInProvider)
        }
    }

    private fun provisionFromFirebaseInNewTransaction(
        firebaseUid: String,
        normalizedEmail: String?,
        emailVerified: Boolean,
        signInProvider: FirebaseSignInProvider
    ): User =
        transactionTemplate.execute {
            provisionFromFirebaseAttempt(
                firebaseUid = firebaseUid,
                normalizedEmail = normalizedEmail,
                emailVerified = emailVerified,
                signInProvider = signInProvider
            )
        }

    private fun provisionFromFirebaseAttempt(
        firebaseUid: String,
        normalizedEmail: String?,
        emailVerified: Boolean,
        signInProvider: FirebaseSignInProvider
    ): User {
        val existingByFirebaseUid = userRepository.findByFirebaseUid(firebaseUid)
        if (existingByFirebaseUid != null) {
            if (existingByFirebaseUid.status == UserStatus.DELETED) {
                throw pendingOrFinalizedDeletionConflict(existingByFirebaseUid, OffsetDateTime.now())
            }

            return updateFirebaseUserEmailIfNeeded(
                user = existingByFirebaseUid,
                normalizedEmail = normalizedEmail,
                emailVerified = emailVerified
            )
        }

        if (normalizedEmail != null) {
            val existingByEmail = userRepository.findByEmail(normalizedEmail)

            if (existingByEmail != null) {
                if (existingByEmail.status == UserStatus.DELETED) {
                    throw pendingOrFinalizedDeletionConflict(existingByEmail, OffsetDateTime.now())
                }

                if (existingByEmail.firebaseUid == null) {
                    if (!emailVerified) {
                        throw DomainConflictException(
                            code = DomainErrorCode.EMAIL_NOT_VERIFIED,
                            message = "Verify your email before linking an existing account"
                        )
                    }
                    existingByEmail.firebaseUid = firebaseUid
                    if (existingByEmail.authOrigin == null) {
                        existingByEmail.authOrigin = AuthOriginPolicy.originFor(signInProvider)
                    }
                    existingByEmail.updatedAt = OffsetDateTime.now()
                    return userRepository.saveAndFlush(existingByEmail)
                }

                if (existingByEmail.firebaseUid == firebaseUid) {
                    return updateFirebaseUserEmailIfNeeded(
                        user = existingByEmail,
                        normalizedEmail = normalizedEmail,
                        emailVerified = emailVerified
                    )
                }

                throw DomainConflictException(
                    code = DomainErrorCode.EMAIL_ALREADY_LINKED_TO_DIFFERENT_FIREBASE_USER,
                    message = "Email is already linked to a different Firebase user"
                )
            }
        }

        return userRepository.saveAndFlush(
            User(
                firebaseUid = firebaseUid,
                email = normalizedEmail,
                authOrigin = AuthOriginPolicy.originFor(signInProvider),
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
        accountDeletionImmediateCleanupService.deleteEphemeralOperationalData(userId)
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
    ): Boolean {
        val user = userRepository.findAllByIdForUpdate(listOf(userId)).singleOrNull()
            ?: return false
        val deletionFinalizesAt = user.deletionFinalizesAt

        if (
            user.status != UserStatus.DELETED ||
            deletionFinalizesAt == null ||
            now.isBefore(deletionFinalizesAt)
        ) {
            return false
        }

        finalizeAccountDeletionUnderLock(
            user = user,
            now = now
        )
        return true
    }

    fun finalizeAccountDeletionNow(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): User {
        val user = userRepository.findAllByIdForUpdate(listOf(userId)).singleOrNull()
            ?: throw DomainConflictException(
                code = DomainErrorCode.USER_NOT_FOUND,
                message = "User was not found"
            )

        if (user.status != UserStatus.DELETED) {
            throw DomainConflictException(
                code = DomainErrorCode.ACCOUNT_DELETION_NOT_PENDING,
                message = "Account deletion is not pending"
            )
        }

        if (user.deletionFinalizesAt == null) {
            return user
        }

        finalizeAccountDeletionUnderLock(
            user = user,
            now = now
        )
        return user
    }

    private fun finalizeAccountDeletionUnderLock(
        user: User,
        now: OffsetDateTime
    ) {
        val userId = user.id

        user.firebaseUid?.let { firebaseUid ->
            when (firebaseExternalAccountService.deleteAccountIfPresent(firebaseUid)) {
                ExternalAccountDeletionResult.DELETED,
                ExternalAccountDeletionResult.ALREADY_ABSENT,
                ExternalAccountDeletionResult.NOT_CONFIGURED -> Unit
            }
        }

        user.email = "deleted.$userId@deleted.reals.local"
        user.firebaseUid = null
        user.deletionFinalizesAt = null
        user.updatedAt = now
        userRepository.save(user)
        auditEventService.record(
            eventType = AuditEventType.ACCOUNT_DELETION_FINALIZED,
            aggregateType = AuditAggregateType.USER,
            aggregateId = userId,
            actorUserId = null
        )
    }

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
        normalizedEmail: String?,
        emailVerified: Boolean
    ): User {
        if (
            !emailVerified ||
            normalizedEmail == null ||
            user.email == normalizedEmail ||
            userRepository.existsByEmail(normalizedEmail)
        ) {
            return user
        }

        user.email = normalizedEmail
        user.updatedAt = OffsetDateTime.now()
        return userRepository.saveAndFlush(user)
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
