package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class AdminSafetyReportControllerIntegrationTest : ControllerIT() {

    @Test
    fun `admin can list and inspect pending safety report with messages`() {
        val setup = createMatchWithFirstChat()
        chatService.sendMessage(setup.firstChatId, setup.userAId, "Hola")
        chatService.sendMessage(setup.firstChatId, setup.userBId, "Mensaje a revisar")

        chatExitService.cancelChatForSafety(
            chatId = setup.firstChatId,
            reporterUserId = setup.userAId,
            reason = ChatExitReason.HARASSMENT,
            details = "Reported harassment"
        )
        val report = safetyReportRepository.findAll().single()
        val admin = userService.createUser("admin-report-list-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/admin/safety-reports")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()", equalTo(1)))
            .andExpect(jsonPath("$[0].id", equalTo(report.id.toString())))
            .andExpect(jsonPath("$[0].status", equalTo("PENDING")))

        mockMvc.perform(
            get("/api/admin/safety-reports/${report.id}")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.report.id", equalTo(report.id.toString())))
            .andExpect(jsonPath("$.report.reason", equalTo("HARASSMENT")))
            .andExpect(jsonPath("$.report.status", equalTo("PENDING")))
            .andExpect(jsonPath("$.reporter.id", equalTo(setup.userAId.toString())))
            .andExpect(jsonPath("$.reported.id", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.messages.length()", equalTo(2)))
            .andExpect(jsonPath("$.penalty").doesNotExist())
    }

    @Test
    fun `admin dismissal moves report to dismissed without penalty`() {
        val setup = createMatchWithFirstChat()
        chatExitService.cancelChatForSafety(
            chatId = setup.firstChatId,
            reporterUserId = setup.userAId,
            details = "Report to dismiss"
        )
        val report = safetyReportRepository.findAll().single()
        val admin = userService.createUser("admin-dismiss-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/admin/safety-reports/${report.id}/dismissal")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content("""{"notes":"No se encontro falta suficiente."}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("DISMISSED")))
            .andExpect(jsonPath("$.reviewedByUserId", equalTo(admin.id.toString())))
            .andExpect(jsonPath("$.reviewedAt", notNullValue()))
            .andExpect(jsonPath("$.verdictNotes", equalTo("No se encontro falta suficiente.")))
            .andExpect(jsonPath("$.penaltyId").doesNotExist())

        val updated = safetyReportRepository.findById(report.id).orElseThrow()
        assertEquals(SafetyReportStatus.DISMISSED, updated.status)
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))
    }

    @Test
    fun `admin temporary penalty confirms report and removes reported user from queue`() {
        val setup = createMatchWithFirstChat()
        chatExitService.cancelChatForSafety(
            chatId = setup.firstChatId,
            reporterUserId = setup.userAId,
            details = "Confirmed unsafe behavior"
        )
        val report = safetyReportRepository.findAll().single()
        val admin = userService.createUser("admin-penalty-${UUID.randomUUID()}@example.com")

        enqueueForMatchmaking(setup.userBId)
        assertTrue(matchmakingQueueRepository.existsByUserId(setup.userBId))

        mockMvc.perform(
            post("/api/admin/safety-reports/${report.id}/penalty")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "type": "TEMPORARY_BAN",
                      "durationHours": 24,
                      "reason": "Harassment confirmed",
                      "notes": "Mensajes ofensivos confirmados."
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("CONFIRMED")))
            .andExpect(jsonPath("$.reviewedByUserId", equalTo(admin.id.toString())))
            .andExpect(jsonPath("$.penaltyId", notNullValue()))

        val updated = safetyReportRepository.findById(report.id).orElseThrow()
        assertNotNull(updated.penaltyId)
        val penaltyId = updated.penaltyId ?: error("Expected penaltyId")
        val penalty = penaltyRepository.findById(penaltyId).orElseThrow()

        assertEquals(SafetyReportStatus.CONFIRMED, updated.status)
        assertEquals(setup.userBId, penalty.userId)
        assertEquals(report.id, penalty.sourceReportId)
        assertEquals(admin.id, penalty.appliedByUserId)
        assertTrue(penalty.active)
        assertFalse(matchmakingQueueRepository.existsByUserId(setup.userBId))
    }

    @Test
    fun `admin cannot review safety report twice`() {
        val setup = createMatchWithFirstChat()
        chatExitService.cancelChatForSafety(
            chatId = setup.firstChatId,
            reporterUserId = setup.userAId,
            details = "Duplicate review guard"
        )
        val report = safetyReportRepository.findAll().single()
        val admin = userService.createUser("admin-review-twice-${UUID.randomUUID()}@example.com")

        safetyReportService.dismissReport(
            reportId = report.id,
            adminUserId = admin.id,
            notes = "Dismissed"
        )

        mockMvc.perform(
            post("/api/admin/safety-reports/${report.id}/penalty")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "type": "TEMPORARY_BAN",
                      "durationHours": 2,
                      "reason": "Late penalty"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("DOMAIN_CONFLICT")))
    }
}
