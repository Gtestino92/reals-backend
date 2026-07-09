package com.reals.backend.integration.service

import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.User
import com.reals.backend.domain.UserStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.identity.ExternalAccountDeletionResult
import com.reals.backend.service.identity.FirebaseExternalAccountService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.OffsetDateTime
import java.util.UUID

class UserDeletionFinalizationIntegrationTest : BaseIT() {

    @MockitoBean
    private lateinit var firebaseExternalAccountService: FirebaseExternalAccountService

    @Test
    fun `successful external deletion finalizes local identifiers and records audit`() {
        val user = createDeletedUser(deadline = OffsetDateTime.now().minusMinutes(1))
        val firebaseUid = user.firebaseUid!!
        Mockito.`when`(firebaseExternalAccountService.deleteAccountIfPresent(firebaseUid))
            .thenReturn(ExternalAccountDeletionResult.DELETED)

        assertTrue(userService.finalizeRecoverableAccountDeletion(user.id))

        Mockito.verify(firebaseExternalAccountService).deleteAccountIfPresent(firebaseUid)
        assertFinalizedLocally(user)
        assertFinalizationAuditExists(user.id)
    }

    @Test
    fun `already absent external account completes retry finalization`() {
        val user = createDeletedUser(deadline = OffsetDateTime.now().minusMinutes(1))
        val firebaseUid = user.firebaseUid!!
        Mockito.`when`(firebaseExternalAccountService.deleteAccountIfPresent(firebaseUid))
            .thenReturn(ExternalAccountDeletionResult.ALREADY_ABSENT)

        assertTrue(userService.finalizeRecoverableAccountDeletion(user.id))

        Mockito.verify(firebaseExternalAccountService).deleteAccountIfPresent(firebaseUid)
        assertFinalizedLocally(user)
        assertFinalizationAuditExists(user.id)
    }

    @Test
    fun `external deletion failure preserves retryable local identity linkage`() {
        val user = createDeletedUser(deadline = OffsetDateTime.now().minusMinutes(1))
        val originalFirebaseUid = user.firebaseUid
        val originalEmail = user.email
        val originalDeadline = user.deletionFinalizesAt
        Mockito.`when`(firebaseExternalAccountService.deleteAccountIfPresent(originalFirebaseUid!!))
            .thenThrow(IllegalStateException("representative Firebase deletion failure"))

        assertThrows<IllegalStateException> {
            userService.finalizeRecoverableAccountDeletion(user.id)
        }

        val unchanged = userRepository.findById(user.id).orElseThrow()
        assertEquals(UserStatus.DELETED, unchanged.status)
        assertEquals(originalFirebaseUid, unchanged.firebaseUid)
        assertEquals(originalEmail, unchanged.email)
        assertEquals(originalDeadline, unchanged.deletionFinalizesAt)
        assertFalse(
            auditEventRepository.findAll().any {
                it.eventType == AuditEventType.ACCOUNT_DELETION_FINALIZED &&
                    it.aggregateId == user.id
            }
        )
    }

    @Test
    fun `active stale candidate is skipped before external deletion`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-active-${UUID.randomUUID()}",
            email = "active-${UUID.randomUUID()}@example.com"
        )

        assertFalse(userService.finalizeRecoverableAccountDeletion(user.id))

        Mockito.verifyNoInteractions(firebaseExternalAccountService)
    }

    @Test
    fun `future deletion deadline is skipped before external deletion`() {
        val user = createDeletedUser(deadline = OffsetDateTime.now().plusDays(1))

        assertFalse(userService.finalizeRecoverableAccountDeletion(user.id))

        Mockito.verifyNoInteractions(firebaseExternalAccountService)
    }

    private fun createDeletedUser(deadline: OffsetDateTime): User {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-finalization-${UUID.randomUUID()}",
            email = "finalization-${UUID.randomUUID()}@example.com"
        )
        user.status = UserStatus.DELETED
        user.deletedAt = OffsetDateTime.now().minusDays(31)
        user.deletionFinalizesAt = deadline
        return userRepository.saveAndFlush(user)
    }

    private fun assertFinalizedLocally(original: User) {
        val finalized = userRepository.findById(original.id).orElseThrow()
        assertEquals(UserStatus.DELETED, finalized.status)
        assertEquals("deleted.${original.id}@deleted.reals.local", finalized.email)
        assertNull(finalized.firebaseUid)
        assertNull(finalized.deletionFinalizesAt)
    }

    private fun assertFinalizationAuditExists(userId: UUID) {
        assertTrue(
            auditEventRepository.findAll().any {
                it.eventType == AuditEventType.ACCOUNT_DELETION_FINALIZED &&
                    it.aggregateId == userId &&
                    it.actorUserId == null &&
                    it.metadataJson == null
            }
        )
    }
}
