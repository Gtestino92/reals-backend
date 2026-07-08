package com.reals.backend.integration.service

import com.reals.backend.domain.UserStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

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
            email = existing.email
        )

        assertEquals(existing.id, linked.id)
        assertEquals(firebaseUid, linked.firebaseUid)
        assertEquals(existing.email, linked.email)
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
}
