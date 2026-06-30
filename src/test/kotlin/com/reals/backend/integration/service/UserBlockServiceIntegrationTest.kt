package com.reals.backend.integration.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class UserBlockServiceIntegrationTest : BaseIT() {

    @Test
    fun `creates user block`() {
        val blocker = userService.createUser("blocker-${UUID.randomUUID()}@example.com")
        val blocked = userService.createUser("blocked-${UUID.randomUUID()}@example.com")

        val block = userBlockService.blockUser(
            blockerUserId = blocker.id,
            blockedUserId = blocked.id,
            source = UserBlockSource.MANUAL
        )

        assertEquals(blocker.id, block.blockerUserId)
        assertEquals(blocked.id, block.blockedUserId)
        assertEquals(UserBlockSource.MANUAL, block.source)
        assertEquals(block.id, userBlockRepository.findByBlockerUserIdAndBlockedUserId(blocker.id, blocked.id)?.id)
    }

    @Test
    fun `block user is idempotent`() {
        val blocker = userService.createUser("block-idempotent-a-${UUID.randomUUID()}@example.com")
        val blocked = userService.createUser("block-idempotent-b-${UUID.randomUUID()}@example.com")

        val first = userBlockService.blockUser(blocker.id, blocked.id, UserBlockSource.MANUAL)
        val second = userBlockService.blockUser(blocker.id, blocked.id, UserBlockSource.ADMIN)

        assertEquals(first.id, second.id)
        assertEquals(1, userBlockRepository.count())
        assertEquals(UserBlockSource.MANUAL, second.source)
        assertEquals(
            1,
            auditEventRepository.findAll()
                .count {
                    it.eventType == AuditEventType.USER_BLOCK_CREATED &&
                        it.aggregateType == AuditAggregateType.USER_BLOCK &&
                        it.aggregateId == first.id
                }
        )
    }

    @Test
    fun `rejects self block`() {
        val user = userService.createUser("self-block-${UUID.randomUUID()}@example.com")

        val exception = assertThrows<DomainBadRequestException> {
            userBlockService.blockUser(
                blockerUserId = user.id,
                blockedUserId = user.id,
                source = UserBlockSource.MANUAL
            )
        }

        assertEquals(DomainErrorCode.USER_BLOCK_SELF_NOT_ALLOWED, exception.code)
    }

    @Test
    fun `blocked pair is true in either direction`() {
        val userA = userService.createUser("block-pair-a-${UUID.randomUUID()}@example.com")
        val userB = userService.createUser("block-pair-b-${UUID.randomUUID()}@example.com")
        val userC = userService.createUser("block-pair-c-${UUID.randomUUID()}@example.com")

        userBlockService.blockUser(
            blockerUserId = userA.id,
            blockedUserId = userB.id,
            source = UserBlockSource.MANUAL
        )

        assertTrue(userBlockService.isBlockedPair(userA.id, userB.id))
        assertTrue(userBlockService.isBlockedPair(userB.id, userA.id))
        assertFalse(userBlockService.isBlockedPair(userA.id, userC.id))
    }
}
