package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportSource
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class SafetyReportControllerIntegrationTest : ControllerIT() {

    @Test
    fun `creates visual profile report and blocks reported user`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.VISUAL_PROFILE,
                        matchId = setup.matchId,
                        details = "Visual profile content is unsafe"
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id", notNullValue()))
            .andExpect(jsonPath("$.reporterUserId", equalTo(setup.userAId.toString())))
            .andExpect(jsonPath("$.reportedUserId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.chatId").doesNotExist())
            .andExpect(jsonPath("$.matchId", equalTo(setup.matchId.toString())))
            .andExpect(jsonPath("$.contextType", equalTo("VISUAL_PROFILE")))
            .andExpect(jsonPath("$.contextId", equalTo(setup.matchId.toString())))

        val report = safetyReportRepository.findAll().single()
        assertEquals(SafetyReportContextType.VISUAL_PROFILE, report.contextType)
        assertEquals(SafetyReportSource.USER, report.source)
        assertEquals(setup.userAId, report.reporterUserId)
        assertNull(report.createdByAdminUserId)
        assertEquals(setup.matchId, report.contextId)
        assertNull(report.chatId)

        val block = userBlockRepository.findByBlockerUserIdAndBlockedUserId(
            blockerUserId = setup.userAId,
            blockedUserId = setup.userBId
        )
        assertNotNull(block)
        assertEquals(UserBlockSource.SAFETY_REPORT, block?.source)
        assertEquals(report.id, block?.sourceReportId)
    }

    @Test
    fun `creates personal message report when reported partner submitted message`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(
            matchId = setup.matchId,
            userId = setup.userBId,
            message = "Unsafe message"
        )

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.PERSONAL_MESSAGE,
                        matchId = setup.matchId,
                        details = "Personal message was unsafe"
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.contextType", equalTo("PERSONAL_MESSAGE")))
            .andExpect(jsonPath("$.contextId", equalTo(setup.matchId.toString())))

        val report = safetyReportRepository.findAll().single()
        assertEquals(SafetyReportContextType.PERSONAL_MESSAGE, report.contextType)
        assertEquals(setup.matchId, report.contextId)
    }

    @Test
    fun `creates profile photo report when photo belongs to matched partner`() {
        val setup = createMatchInVisualPhase()
        val reportedProfile = profileRepository.findByUserId(setup.userBId)
            ?: error("Expected reported profile")
        val reportedPhoto = profilePhotoRepository.findByProfileId(reportedProfile.id).first()

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.PROFILE_PHOTO,
                        matchId = setup.matchId,
                        profilePhotoId = reportedPhoto.id,
                        details = "Profile photo was unsafe"
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.contextType", equalTo("PROFILE_PHOTO")))
            .andExpect(jsonPath("$.contextId", equalTo(reportedPhoto.id.toString())))

        val report = safetyReportRepository.findAll().single()
        assertEquals(SafetyReportContextType.PROFILE_PHOTO, report.contextType)
        assertEquals(reportedPhoto.id, report.contextId)
    }

    @Test
    fun `creates chat report and contains active chat`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.CHAT,
                        chatId = setup.firstChatId,
                        reason = SafetyReportReason.CHILD_SAFETY_CONCERN,
                        details = "Chat content was unsafe"
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.chatId", equalTo(setup.firstChatId.toString())))
            .andExpect(jsonPath("$.contextType", equalTo("CHAT")))
            .andExpect(jsonPath("$.contextId", equalTo(setup.firstChatId.toString())))

        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        assertEquals(ChatStatus.CANCELLED, chat.status)
        assertEquals(ChatEndReason.USER_BLOCK, chat.endedReason)

        val report = safetyReportRepository.findAll().single()
        assertEquals(SafetyReportReason.CHILD_SAFETY_CONCERN, report.reason)
        assertEquals(com.reals.backend.domain.SafetyReportStatus.PENDING, report.status)
        assertNotNull(
            userBlockRepository.findByBlockerUserIdAndBlockedUserId(
                blockerUserId = setup.userAId,
                blockedUserId = setup.userBId
            )
        )
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))

        val match = matchRepository.findById(setup.matchId).orElseThrow()
        assertEquals(MatchState.CHAT_REJECTED, match.state)
    }

    @Test
    fun `rejects duplicate user report for same reporter reported and context`() {
        val setup = createMatchInVisualPhase()
        val body = reportJson(
            reportedUserId = setup.userBId,
            contextType = SafetyReportContextType.VISUAL_PROFILE,
            matchId = setup.matchId,
            details = "Repeated report"
        )

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(body)
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(body)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SAFETY_REPORT_ALREADY_EXISTS")))

        assertEquals(1, safetyReportRepository.findAll().size)
        assertEquals(1, userBlockRepository.findAll().size)
    }

    @Test
    fun `different context type with same match can create separate report`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(
            matchId = setup.matchId,
            userId = setup.userBId,
            message = "Unsafe message"
        )

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.VISUAL_PROFILE,
                        matchId = setup.matchId,
                        details = "Unsafe profile"
                    )
                )
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.PERSONAL_MESSAGE,
                        matchId = setup.matchId,
                        details = "Unsafe personal message"
                    )
                )
        )
            .andExpect(status().isCreated)

        assertEquals(2, safetyReportRepository.findAll().size)
        assertEquals(1, userBlockRepository.findAll().size)
    }

    @Test
    fun `rejects report when reporter and reported have no interaction`() {
        val setup = createMatchInVisualPhase()
        val stranger = createActiveProfile(
            email = "stranger-${UUID.randomUUID()}@example.com",
            displayName = "Stranger",
            gender = com.reals.backend.domain.Gender.MALE,
            lookingForGenders = setOf(com.reals.backend.domain.Gender.FEMALE)
        )

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(stranger))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.VISUAL_PROFILE,
                        matchId = setup.matchId,
                        details = "No relationship"
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("SAFETY_REPORT_CONTEXT_INVALID")))
    }

    @Test
    fun `rejects report when requested reported user disagrees with context`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userAId,
                        contextType = SafetyReportContextType.VISUAL_PROFILE,
                        matchId = setup.matchId,
                        details = "Wrong reported user"
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("SAFETY_REPORT_CONTEXT_INVALID")))
    }

    @Test
    fun `rejects missing required context id`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.CHAT,
                        details = "Missing chat id"
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("SAFETY_REPORT_CONTEXT_INVALID")))
    }

    @Test
    fun `rejects invalid details`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.VISUAL_PROFILE,
                        matchId = setup.matchId,
                        details = "<script>"
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `existing block does not prevent report creation`() {
        val setup = createMatchInVisualPhase()
        userBlockService.blockUser(
            blockerUserId = setup.userAId,
            blockedUserId = setup.userBId,
            source = UserBlockSource.MANUAL
        )

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    reportJson(
                        reportedUserId = setup.userBId,
                        contextType = SafetyReportContextType.VISUAL_PROFILE,
                        matchId = setup.matchId,
                        details = "Unsafe profile with existing block"
                    )
                )
        )
            .andExpect(status().isCreated)

        assertEquals(1, safetyReportRepository.findAll().size)
        assertEquals(1, userBlockRepository.findAll().size)
        assertTrue(userBlockService.isBlockedPair(setup.userAId, setup.userBId))
    }

    private fun reportJson(
        reportedUserId: UUID,
        contextType: SafetyReportContextType,
        chatId: UUID? = null,
        matchId: UUID? = null,
        profilePhotoId: UUID? = null,
        reason: SafetyReportReason = SafetyReportReason.INAPPROPRIATE_BEHAVIOR,
        details: String
    ): String {
        val fields = mutableMapOf<String, Any>(
            "reportedUserId" to reportedUserId,
            "contextType" to contextType,
            "reason" to reason,
            "details" to details
        )
        chatId?.let { fields["chatId"] = it }
        matchId?.let { fields["matchId"] = it }
        profilePhotoId?.let { fields["profilePhotoId"] = it }
        return jsonBody(fields)
    }
}
