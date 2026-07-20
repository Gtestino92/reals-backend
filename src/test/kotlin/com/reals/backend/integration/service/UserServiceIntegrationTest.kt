package com.reals.backend.integration.service

import com.reals.backend.domain.UserStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserServiceIntegrationTest : BaseIT() {

    @Test
    fun `provision from firebase creates user from firebase uid and email`() {
        val firebaseUid = "firebase-${UUID.randomUUID()}"

        val user = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = "Firebase.User@Example.com"
        )

        assertEquals(firebaseUid, user.firebaseUid)
        assertEquals("firebase.user@example.com", user.email)
        assertNotNull(user.id)
    }

    @Test
    fun `provision from firebase links existing email user to firebase uid`() {
        val existing = userService.createUser("linked-${UUID.randomUUID()}@example.com")
        val firebaseUid = "firebase-${UUID.randomUUID()}"

        val linked = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = existing.email,
            emailVerified = true
        )

        assertEquals(existing.id, linked.id)
        assertEquals(firebaseUid, linked.firebaseUid)
        assertEquals(existing.email, linked.email)
    }

    @Test
    fun `provision from firebase rejects unverified legacy email link without mutation`() {
        val existing = userService.createUser("unverified-link-${UUID.randomUUID()}@example.com")
        val firebaseUid = "firebase-${UUID.randomUUID()}"

        val exception = assertThrows<DomainConflictException> {
            userService.provisionFromFirebase(
                firebaseUid = firebaseUid,
                email = existing.email,
                emailVerified = false
            )
        }

        assertEquals(DomainErrorCode.EMAIL_NOT_VERIFIED, exception.code)
        val unchanged = userRepository.findById(existing.id).orElseThrow()
        assertNull(unchanged.firebaseUid)
        assertEquals(existing.email, unchanged.email)
    }

    @Test
    fun `provision from firebase creates new user with unverified email`() {
        val firebaseUid = "firebase-unverified-new-${UUID.randomUUID()}"
        val user = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = "unverified-new-${UUID.randomUUID()}@example.com",
            emailVerified = false
        )

        assertEquals(firebaseUid, user.firebaseUid)
        assertNotNull(user.email)
    }

    @Test
    fun `provision from firebase existing uid does not require verified email`() {
        val firebaseUid = "firebase-existing-unverified-${UUID.randomUUID()}"
        val user = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = "existing-unverified-${UUID.randomUUID()}@example.com",
            emailVerified = false
        )

        val loaded = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = user.email,
            emailVerified = false
        )

        assertEquals(user.id, loaded.id)
    }

    @Test
    fun `provision from firebase does not sync unverified changed email for existing uid`() {
        val firebaseUid = "firebase-unverified-change-${UUID.randomUUID()}"
        val originalEmail = "original-${UUID.randomUUID()}@example.com"
        val user = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = originalEmail,
            emailVerified = false
        )

        userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = "changed-${UUID.randomUUID()}@example.com",
            emailVerified = false
        )

        assertEquals(originalEmail, userRepository.findById(user.id).orElseThrow().email)
    }

    @Test
    fun `provision from firebase syncs verified changed email when unique`() {
        val firebaseUid = "firebase-verified-change-${UUID.randomUUID()}"
        val user = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = "verified-original-${UUID.randomUUID()}@example.com",
            emailVerified = false
        )
        val changedEmail = "verified-changed-${UUID.randomUUID()}@example.com"

        val updated = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = changedEmail,
            emailVerified = true
        )

        assertEquals(user.id, updated.id)
        assertEquals(changedEmail, userRepository.findById(user.id).orElseThrow().email)
    }

    @Test
    fun `concurrent firebase provisioning with same uid and email returns same user`() {
        val firebaseUid = "firebase-concurrent-${UUID.randomUUID()}"
        val email = "firebase-concurrent-${UUID.randomUUID()}@example.com"

        val outcomes = runProvisioningConcurrently(
            { userService.provisionFromFirebase(firebaseUid, email) },
            { userService.provisionFromFirebase(firebaseUid, email) }
        )

        assertTrue(outcomes.all { it.value != null }, outcomes.toString())
        assertEquals(1, outcomes.map { it.value!!.id }.toSet().size)
        assertEquals(1, userRepository.findAll().count { it.firebaseUid == firebaseUid })
    }

    @Test
    fun `concurrent firebase provisioning preserves merge by email for unlinked user`() {
        val existing = userService.createUser("concurrent-link-${UUID.randomUUID()}@example.com")
        val firebaseUid = "firebase-concurrent-link-${UUID.randomUUID()}"

        val outcomes = runProvisioningConcurrently(
            { userService.provisionFromFirebase(firebaseUid, existing.email, emailVerified = true) },
            { userService.provisionFromFirebase(firebaseUid, existing.email, emailVerified = true) }
        )

        assertTrue(outcomes.all { it.value != null }, outcomes.toString())
        assertEquals(setOf(existing.id), outcomes.map { it.value!!.id }.toSet())
        assertEquals(1, userRepository.findAll().count { it.firebaseUid == firebaseUid })
    }

    @Test
    fun `provision does not link incompatible existing firebase identity`() {
        val existing = userService.provisionFromFirebase(
            firebaseUid = "firebase-existing-${UUID.randomUUID()}",
            email = "firebase-existing-${UUID.randomUUID()}@example.com"
        )

        val exception = assertThrows<IllegalStateException> {
            userService.provisionFromFirebase(
                firebaseUid = "firebase-incompatible-${UUID.randomUUID()}",
                email = existing.email
            )
        }

        assertEquals("Email already belongs to another Firebase user: ${existing.email}", exception.message)
        assertEquals(existing.firebaseUid, userRepository.findById(existing.id).orElseThrow().firebaseUid)
    }

    @Test
    fun `provision cannot recreate deleted user during recovery by firebase uid`() {
        val firebaseUid = "firebase-pending-${UUID.randomUUID()}"
        val user = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = "pending-${UUID.randomUUID()}@example.com"
        )
        user.status = UserStatus.DELETED
        user.deletionFinalizesAt = OffsetDateTime.now().plusDays(1)
        userRepository.saveAndFlush(user)
        val userCount = userRepository.count()

        val exception = assertThrows<DomainConflictException> {
            userService.provisionFromFirebase(firebaseUid, user.email)
        }

        assertEquals(DomainErrorCode.ACCOUNT_PENDING_DELETION, exception.code)
        assertEquals(userCount, userRepository.count())
    }

    @Test
    fun `provision cannot recreate expired deleted user by firebase uid`() {
        val firebaseUid = "firebase-expired-${UUID.randomUUID()}"
        val user = userService.provisionFromFirebase(
            firebaseUid = firebaseUid,
            email = "expired-${UUID.randomUUID()}@example.com"
        )
        user.status = UserStatus.DELETED
        user.deletionFinalizesAt = OffsetDateTime.now().minusMinutes(1)
        userRepository.saveAndFlush(user)
        val userCount = userRepository.count()

        val exception = assertThrows<DomainConflictException> {
            userService.provisionFromFirebase(firebaseUid, user.email)
        }

        assertEquals(DomainErrorCode.ACCOUNT_DELETION_FINALIZED, exception.code)
        assertEquals(userCount, userRepository.count())
    }

    @Test
    fun `provision cannot recreate expired deleted user by normalized email`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-expired-email-${UUID.randomUUID()}",
            email = "expired-email-${UUID.randomUUID()}@example.com"
        )
        user.status = UserStatus.DELETED
        user.deletionFinalizesAt = OffsetDateTime.now().minusMinutes(1)
        userRepository.saveAndFlush(user)
        val userCount = userRepository.count()

        val exception = assertThrows<DomainConflictException> {
            userService.provisionFromFirebase(
                firebaseUid = "different-firebase-${UUID.randomUUID()}",
                email = user.email!!.uppercase()
            )
        }

        assertEquals(DomainErrorCode.ACCOUNT_DELETION_FINALIZED, exception.code)
        assertEquals(userCount, userRepository.count())
    }

    private fun runProvisioningConcurrently(vararg actions: () -> com.reals.backend.domain.User): List<ProvisionOutcome> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(actions.size)

        try {
            val futures = actions.map { action ->
                executor.submit(
                    Callable {
                        start.await()
                        try {
                            ProvisionOutcome(value = action(), throwable = null)
                        } catch (ex: Throwable) {
                            ProvisionOutcome(value = null, throwable = ex)
                        }
                    }
                )
            }

            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private data class ProvisionOutcome(
        val value: com.reals.backend.domain.User?,
        val throwable: Throwable?
    )
}
