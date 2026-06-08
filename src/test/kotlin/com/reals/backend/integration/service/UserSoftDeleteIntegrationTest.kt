package com.reals.backend.integration.service

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.UserStatus
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class UserSoftDeleteIntegrationTest : BaseIT() {

    @Test
    fun `delete user soft deletes account and closes active first chat match`() {
        val setup = createMatchWithFirstChat(emailPrefix = "soft-delete-first-chat")

        userService.deleteUser(setup.userAId)

        val deletedUser = userService.findByIdOrThrow(setup.userAId)
        assertEquals(UserStatus.DELETED, deletedUser.status)
        assertNotNull(deletedUser.deletedAt)
        assertEquals("deleted.${setup.userAId}@deleted.reals.local", deletedUser.email)

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
    fun `delete user cancels available second chat and keeps historical chat visible`() {
        val setup = createAvailableSecondChat()
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
}
