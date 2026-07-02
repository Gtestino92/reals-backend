package com.reals.backend.integration.service

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.UserStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class UserSoftDeleteIntegrationTest : BaseIT() {

    @Test
    fun `delete user soft deletes account and closes active first chat match`() {
        val setup = createMatchWithFirstChat(emailPrefix = "soft-delete-first-chat")
        val originalEmail = userService.findByIdOrThrow(setup.userAId).email

        userService.deleteUser(setup.userAId)

        val deletedUser = userService.findByIdOrThrow(setup.userAId)
        assertEquals(UserStatus.DELETED, deletedUser.status)
        assertNotNull(deletedUser.deletedAt)
        assertNotNull(deletedUser.deletionFinalizesAt)
        assertTrue(deletedUser.deletionFinalizesAt!!.isAfter(deletedUser.deletedAt))
        assertEquals(originalEmail, deletedUser.email)
        assertEquals(ProfileStatus.DRAFT, profileService.findByUserId(setup.userAId)!!.status)

        assertEquals(
            ChatStatus.CANCELLED,
            chatRepository.findById(setup.firstChatId).orElseThrow().status
        )
        assertEquals(
            MatchState.CHAT_REJECTED,
            matchService.findByIdOrThrow(setup.matchId).state
        )
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.MATCH))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.MATCH))
    }

    @Test
    fun `delete user closes scheduling connection and releases locks for both users`() {
        val setup = createConnectionInSchedulingPhase()

        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTime = futureHalfHourSlot()
        )

        userService.deleteUser(setup.userAId)

        assertEquals(
            MatchState.VISUAL_APPROVED,
            matchService.findByIdOrThrow(setup.matchId).state
        )
        assertEquals(
            ConnectionState.CLOSED,
            connectionService.findByIdOrThrow(setup.connectionId).state
        )
        assertEquals(
            NegotiationStatus.FAILED,
            schedulingService.findNegotiationOrThrow(setup.connectionId).status
        )
        assertNoConnectionLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `delete user cancels second chat and keeps historical chat visible`() {
        val setup = createActiveSecondChat()
        val secondChat = chatRepository.findByConnectionIdAndChatType(
            connectionId = setup.connectionId,
            chatType = ChatType.SECOND_CHAT
        ) ?: error("Second chat was not created")

        userService.deleteUser(setup.userAId)

        assertEquals(
            ChatStatus.CANCELLED,
            chatRepository.findById(secondChat.id).orElseThrow().status
        )
        assertEquals(
            ConnectionState.CLOSED,
            connectionService.findByIdOrThrow(setup.connectionId).state
        )
        assertFalse(chatRepository.findById(secondChat.id).isEmpty)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `delete user removes user from matchmaking queue`() {
        val userId = createActiveProfile(
            email = "soft-delete-queued@example.com",
            displayName = "Queued Delete",
            gender = com.reals.backend.domain.Gender.FEMALE,
            lookingForGender = com.reals.backend.domain.LookingForGender.MEN
        )
        enqueueForMatchmaking(userId)

        userService.deleteUser(userId)

        assertFalse(matchmakingQueueRepository.existsByUserId(userId))
    }

    @Test
    fun `delete user keeps profile photos and moves profile to draft`() {
        val userId = createActiveProfile(
            email = "soft-delete-profile-${UUID.randomUUID()}@example.com",
            displayName = "Profile Delete",
            gender = com.reals.backend.domain.Gender.FEMALE,
            lookingForGender = com.reals.backend.domain.LookingForGender.MEN
        )
        val profile = profileService.findByUserId(userId)!!
        val photosBeforeDelete = profileService.getPhotos(profile.id)

        userService.deleteUser(userId)

        val deletedProfile = profileService.findByIdOrThrow(profile.id)
        assertEquals(ProfileStatus.DRAFT, deletedProfile.status)
        assertEquals(photosBeforeDelete.map { it.id }, profileService.getPhotos(profile.id).map { it.id })
    }

    @Test
    fun `reactivate user within recovery window restores account but keeps profile draft`() {
        val userId = createActiveProfile(
            email = "reactivate-${UUID.randomUUID()}@example.com",
            displayName = "Reactivate",
            gender = com.reals.backend.domain.Gender.FEMALE,
            lookingForGender = com.reals.backend.domain.LookingForGender.MEN
        )

        userService.deleteUser(userId)

        val reactivated = userService.reactivateUser(userId)

        assertEquals(UserStatus.ACTIVE, reactivated.status)
        assertNull(reactivated.deletedAt)
        assertNull(reactivated.deletionFinalizesAt)
        assertEquals(ProfileStatus.DRAFT, profileService.findByUserId(userId)!!.status)
        assertFalse(matchmakingQueueRepository.existsByUserId(userId))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userId, EngagementType.MATCH))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userId, EngagementType.CONNECTION))
    }

    @Test
    fun `reactivate finalized deletion returns domain conflict`() {
        val user = userService.createUser("finalized-reactivation-${UUID.randomUUID()}@example.com")
        userService.deleteUser(user.id)

        val deletedUser = userRepository.findById(user.id).orElseThrow()
        deletedUser.deletionFinalizesAt = OffsetDateTime.now().minusMinutes(1)
        userRepository.saveAndFlush(deletedUser)

        val exception = assertThrows<DomainConflictException> {
            userService.reactivateUser(user.id)
        }

        assertEquals(DomainErrorCode.ACCOUNT_DELETION_FINALIZED, exception.code)
    }

    @Test
    fun `finalize recoverable account deletions anonymizes expired deleted users`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-finalize-${UUID.randomUUID()}",
            email = "finalize-${UUID.randomUUID()}@example.com"
        )
        userService.deleteUser(user.id)

        val deletedUser = userRepository.findById(user.id).orElseThrow()
        deletedUser.deletionFinalizesAt = OffsetDateTime.now().minusMinutes(1)
        userRepository.saveAndFlush(deletedUser)

        val finalizedCount = userService.finalizeRecoverableAccountDeletions()

        val finalizedUser = userRepository.findById(user.id).orElseThrow()
        assertEquals(1, finalizedCount)
        assertEquals(UserStatus.DELETED, finalizedUser.status)
        assertEquals("deleted.${user.id}@deleted.reals.local", finalizedUser.email)
        assertNull(finalizedUser.firebaseUid)
        assertNull(finalizedUser.deletionFinalizesAt)
        assertFalse(userService.finalizeRecoverableAccountDeletion(user.id))
    }

    @Test
    fun `single account deletion finalization is idempotent`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-finalize-single-${UUID.randomUUID()}",
            email = "finalize-single-${UUID.randomUUID()}@example.com"
        )
        userService.deleteUser(user.id)

        val deletedUser = userRepository.findById(user.id).orElseThrow()
        deletedUser.deletionFinalizesAt = OffsetDateTime.now().minusMinutes(1)
        userRepository.saveAndFlush(deletedUser)

        assertTrue(userService.finalizeRecoverableAccountDeletion(user.id))
        assertFalse(userService.finalizeRecoverableAccountDeletion(user.id))

        val finalizedUser = userRepository.findById(user.id).orElseThrow()
        assertEquals(UserStatus.DELETED, finalizedUser.status)
        assertEquals("deleted.${user.id}@deleted.reals.local", finalizedUser.email)
        assertNull(finalizedUser.firebaseUid)
        assertNull(finalizedUser.deletionFinalizesAt)
    }
}
