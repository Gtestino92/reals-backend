package com.reals.backend.service

import com.reals.backend.domain.User
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.identity.FirebaseExternalAccountService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class UserServiceProvisioningRetryTest {

    @Test
    fun `provisioning retry is limited to one fresh transaction`() {
        val userRepository = Mockito.mock(UserRepository::class.java)
        val transactionManager = CountingTransactionManager()
        val service = userService(
            userRepository = userRepository,
            transactionTemplate = TransactionTemplate(transactionManager)
        )

        Mockito.`when`(userRepository.findByFirebaseUid("firebase-retry-limit"))
            .thenReturn(null)
        Mockito.`when`(userRepository.findByEmail("retry-limit@example.com"))
            .thenReturn(null)
        Mockito.`when`(userRepository.saveAndFlush(Mockito.any(User::class.java)))
            .thenThrow(DataIntegrityViolationException("first unique conflict"))
            .thenThrow(DataIntegrityViolationException("second unique conflict"))

        assertThrows<DataIntegrityViolationException> {
            service.provisionFromFirebase(
                firebaseUid = "firebase-retry-limit",
                email = "retry-limit@example.com"
            )
        }

        assertEquals(2, transactionManager.begins)
        Mockito.verify(userRepository, Mockito.times(2)).saveAndFlush(Mockito.any(User::class.java))
    }

    private fun userService(
        userRepository: UserRepository,
        transactionTemplate: TransactionTemplate
    ): UserService =
        UserService(
            userRepository = userRepository,
            profileRepository = Mockito.mock(ProfileRepository::class.java),
            accountDeletionService = Mockito.mock(AccountDeletionService::class.java),
            accountDeletionImmediateCleanupService = Mockito.mock(AccountDeletionImmediateCleanupService::class.java),
            firebaseExternalAccountService = Mockito.mock(FirebaseExternalAccountService::class.java),
            auditEventService = Mockito.mock(AuditEventService::class.java),
            homeStateInvalidationService = Mockito.mock(HomeStateInvalidationService::class.java),
            transactionManager = transactionTemplate.transactionManager!!,
            accountDeletionRecoveryWindowDays = 30
        )

    private class CountingTransactionManager : AbstractPlatformTransactionManager() {
        var begins: Int = 0
            private set

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            begins += 1
        }

        override fun doCommit(status: DefaultTransactionStatus) = Unit

        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }
}
