package com.reals.backend.integration.controller

import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportSource
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class UserBlockControllerIntegrationTest : ControllerIT() {

    @Test
    fun `participant creates manual block and replay is idempotent`() {
        val setup = createMatchWithFirstChat()

        val firstResult = mockMvc.perform(
            post("/api/matches/${setup.matchId}/block")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id", notNullValue()))
            .andExpect(jsonPath("$.source", equalTo(UserBlockSource.MANUAL.name)))
            .andExpect(jsonPath("$.createdAt", notNullValue()))
            .andExpect(jsonPath("$.blockerUserId").doesNotExist())
            .andExpect(jsonPath("$.blockedUserId").doesNotExist())
            .andExpect(jsonPath("$.firebaseUid").doesNotExist())
            .andExpect(jsonPath("$.email").doesNotExist())
            .andReturn()

        val blockId = responseId(firstResult.response.contentAsString)

        val blocks = userBlockRepository.findAll()
        assertEquals(1, blocks.size)
        assertEquals(setup.userAId, blocks.single().blockerUserId)
        assertEquals(setup.userBId, blocks.single().blockedUserId)
        assertEquals(UserBlockSource.MANUAL, blocks.single().source)

        assertEquals(0, safetyReportRepository.count())
        assertEquals(0, penaltyRepository.count())
        assertEquals(0, userReliabilityEventRepository.count())
        assertUserBlockCreatedAuditCount(blockId, expected = 1)

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/block")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(blockId.toString())))
            .andExpect(jsonPath("$.source", equalTo(UserBlockSource.MANUAL.name)))

        assertEquals(1, userBlockRepository.findAll().size)
        assertEquals(0, safetyReportRepository.count())
        assertEquals(0, penaltyRepository.count())
        assertEquals(0, userReliabilityEventRepository.count())
        assertUserBlockCreatedAuditCount(blockId, expected = 1)
    }

    @Test
    fun `manual endpoint returns existing safety report block without rewriting source`() {
        val setup = createMatchWithFirstChat()
        val report = safetyReportRepository.saveAndFlush(
            SafetyReport(
                reporterUserId = setup.userAId,
                reportedUserId = setup.userBId,
                chatId = setup.firstChatId,
                matchId = setup.matchId,
                source = SafetyReportSource.USER,
                contextType = SafetyReportContextType.CHAT,
                contextId = setup.firstChatId,
                reason = SafetyReportReason.INAPPROPRIATE_BEHAVIOR,
                details = "Existing safety report"
            )
        )
        val existingBlock = userBlockService.blockUser(
            blockerUserId = setup.userAId,
            blockedUserId = setup.userBId,
            source = UserBlockSource.SAFETY_REPORT,
            sourceReportId = report.id
        )

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/block")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(existingBlock.id.toString())))
            .andExpect(jsonPath("$.source", equalTo(UserBlockSource.SAFETY_REPORT.name)))

        val block = userBlockRepository.findByBlockerUserIdAndBlockedUserId(setup.userAId, setup.userBId)
        assertNotNull(block)
        assertEquals(UserBlockSource.SAFETY_REPORT, block?.source)
        assertEquals(existingBlock.id, block?.id)
        assertEquals(1, userBlockRepository.findAll().size)
    }

    @Test
    fun `non participant cannot manually block match`() {
        val setup = createMatchWithFirstChat()
        val stranger = userService.createUser("block-stranger-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/block")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code", equalTo("ACCESS_DENIED")))
    }

    private fun responseId(content: String): UUID {
        val tree = objectMapper.readTree(content)
        return UUID.fromString(tree.get("id").asString())
    }

    private fun assertUserBlockCreatedAuditCount(blockId: UUID, expected: Int) {
        assertEquals(
            expected,
            auditEventRepository.findAll().count {
                it.eventType == AuditEventType.USER_BLOCK_CREATED && it.aggregateId == blockId
            }
        )
    }
}
