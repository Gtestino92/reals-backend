package com.reals.backend.integration.controller

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportSource
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.UUID

class AdminSafetyReportControllerIntegrationTest : ControllerIT() {

    @Test
    fun `pending child safety reports are prioritized and reviewed reports are not`() {
        val reported = userService.createUser("priority-reported-${UUID.randomUUID()}@example.com")
        val reporter = userService.createUser("priority-reporter-${UUID.randomUUID()}@example.com")
        val admin = userService.createUser("priority-admin-${UUID.randomUUID()}@example.com")
        val now = OffsetDateTime.now()
        val childReport = safetyReportRepository.save(
            SafetyReport(
                reporterUserId = reporter.id,
                reportedUserId = reported.id,
                contextType = SafetyReportContextType.USER,
                contextId = reported.id,
                reason = SafetyReportReason.CHILD_SAFETY_CONCERN,
                details = "Child-safety concern",
                createdAt = now.minusDays(1)
            )
        )
        val normalReport = safetyReportRepository.save(
            SafetyReport(
                reporterUserId = reporter.id,
                reportedUserId = reported.id,
                contextType = SafetyReportContextType.USER,
                contextId = UUID.randomUUID(),
                reason = SafetyReportReason.HARASSMENT,
                details = "Newer standard concern",
                createdAt = now
            )
        )

        mockMvc.perform(
            get("/api/admin/safety-reports/pending")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id", equalTo(childReport.id.toString())))
            .andExpect(jsonPath("$[0].priorityReview", equalTo(true)))
            .andExpect(jsonPath("$[1].id", equalTo(normalReport.id.toString())))
            .andExpect(jsonPath("$[1].priorityReview", equalTo(false)))

        safetyReportService.dismissReport(childReport.id, admin.id, "Reviewed")

        mockMvc.perform(
            get("/api/admin/safety-reports/${childReport.id}")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.report.priorityReview", equalTo(false)))
    }

    @Test
    fun `admin can create general user report without reporter`() {
        val reported = createActiveProfile(
            email = "admin-user-report-${UUID.randomUUID()}@example.com",
            displayName = "Reported User",
            gender = com.reals.backend.domain.Gender.MALE,
            lookingForGenders = setOf(com.reals.backend.domain.Gender.FEMALE)
        )
        val admin = userService.createUser("admin-create-user-report-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/admin/safety-reports")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "reportedUserId" to reported,
                            "contextType" to "USER",
                            "reason" to "OTHER",
                            "details" to "Backoffice-created user safety note"
                        )
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.report.source", equalTo("ADMIN")))
            .andExpect(jsonPath("$.report.reporter").doesNotExist())
            .andExpect(jsonPath("$.report.reported.userId", equalTo(reported.toString())))
            .andExpect(jsonPath("$.report.contextType", equalTo("USER")))
            .andExpect(jsonPath("$.report.contextId", equalTo(reported.toString())))
            .andExpect(jsonPath("$.report.matchId").doesNotExist())
            .andExpect(jsonPath("$.details", equalTo("Backoffice-created user safety note")))

        val report = safetyReportRepository.findAll().single()
        assertEquals(SafetyReportSource.ADMIN, report.source)
        assertEquals(admin.id, report.createdByAdminUserId)
        assertNull(report.reporterUserId)
        assertNull(report.matchId)
        assertEquals(SafetyReportContextType.USER, report.contextType)
        assertEquals(reported, report.contextId)
        assertEquals(0, userBlockRepository.count())
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(reported))

        val snapshot = safetyReportEvidenceSnapshotRepository.findBySafetyReportId(report.id)
            ?: error("Expected evidence snapshot")
        assertEquals(0, snapshot.messageCount)
        assertNull(snapshot.transcriptSha256)

        val audit = auditEventRepository.findAll()
            .single {
                it.eventType == AuditEventType.SAFETY_REPORT_CREATED &&
                    it.aggregateType == AuditAggregateType.SAFETY_REPORT &&
                    it.aggregateId == report.id
            }
        assertEquals(admin.id, audit.actorUserId)
        assertEquals(reported, audit.targetUserId)
        assertTrue(audit.metadataJson!!.contains("ADMIN"))
        assertFalse(audit.metadataJson!!.contains("Backoffice-created user safety note"))
    }

    @Test
    fun `admin can create contextual report with reporter`() {
        val setup = createMatchInVisualPhase()
        val admin = userService.createUser("admin-context-with-reporter-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/admin/safety-reports")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "reportedUserId" to setup.userBId,
                            "reporterUserId" to setup.userAId,
                            "contextType" to "VISUAL_PROFILE",
                            "matchId" to setup.matchId,
                            "reason" to "INAPPROPRIATE_BEHAVIOR",
                            "details" to "Admin contextual report"
                        )
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.report.source", equalTo("ADMIN")))
            .andExpect(jsonPath("$.report.reporter.userId", equalTo(setup.userAId.toString())))
            .andExpect(jsonPath("$.report.reported.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.report.matchId", equalTo(setup.matchId.toString())))

        val report = safetyReportRepository.findAll().single()
        assertEquals(SafetyReportSource.ADMIN, report.source)
        assertEquals(setup.userAId, report.reporterUserId)
        assertEquals(admin.id, report.createdByAdminUserId)
        assertEquals(0, userBlockRepository.count())
    }

    @Test
    fun `admin report can coexist with user report for same reporter reported and context`() {
        val setup = createMatchInVisualPhase()
        val admin = userService.createUser("admin-user-coexist-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/safety/reports")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "reportedUserId" to setup.userBId,
                            "contextType" to "VISUAL_PROFILE",
                            "matchId" to setup.matchId,
                            "reason" to "INAPPROPRIATE_BEHAVIOR",
                            "details" to "User-created report"
                        )
                    )
                )
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/admin/safety-reports")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "reportedUserId" to setup.userBId,
                            "reporterUserId" to setup.userAId,
                            "contextType" to "VISUAL_PROFILE",
                            "matchId" to setup.matchId,
                            "reason" to "OTHER",
                            "details" to "Admin-created report for same context"
                        )
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.report.source", equalTo("ADMIN")))
            .andExpect(jsonPath("$.report.reporter.userId", equalTo(setup.userAId.toString())))
            .andExpect(jsonPath("$.report.reported.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.report.contextId", equalTo(setup.matchId.toString())))

        val reports = safetyReportRepository.findAll()
        assertEquals(2, reports.size)
        assertEquals(
            setOf(SafetyReportSource.USER, SafetyReportSource.ADMIN),
            reports.map { it.source }.toSet()
        )
        assertEquals(1, userBlockRepository.count())
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))
        assertEquals(0, userReliabilityEventRepository.count())
    }

    @Test
    fun `admin can create contextual report without reporter when reported user belongs to context`() {
        val setup = createMatchInVisualPhase()
        val admin = userService.createUser("admin-context-no-reporter-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/admin/safety-reports")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "reportedUserId" to setup.userBId,
                            "contextType" to "VISUAL_PROFILE",
                            "matchId" to setup.matchId,
                            "reason" to "OTHER",
                            "details" to "Context without user reporter"
                        )
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.report.reporter").doesNotExist())
            .andExpect(jsonPath("$.report.matchId", equalTo(setup.matchId.toString())))

        val report = safetyReportRepository.findAll().single()
        assertNull(report.reporterUserId)
        assertEquals(SafetyReportSource.ADMIN, report.source)
    }

    @Test
    fun `admin-created contextual chat report does not close chat or create block`() {
        val setup = createMatchWithFirstChat()
        val admin = userService.createUser("admin-chat-no-side-effects-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/admin/safety-reports")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "reportedUserId" to setup.userBId,
                            "contextType" to "CHAT",
                            "chatId" to setup.firstChatId,
                            "reason" to "HARASSMENT",
                            "details" to "Admin chat report"
                        )
                    )
                )
        )
            .andExpect(status().isCreated)

        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(setup.firstChatId).orElseThrow().status)
        assertEquals(0, userBlockRepository.count())
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))
        assertEquals(0, userReliabilityEventRepository.count())

        val report = safetyReportRepository.findAll().single()
        val snapshot = safetyReportEvidenceSnapshotRepository.findBySafetyReportId(report.id)
            ?: error("Expected evidence snapshot")
        assertEquals(setup.firstChatId, snapshot.chatId)
    }

    @Test
    fun `admin creation rejects invalid users and context mismatch`() {
        val setup = createMatchInVisualPhase()
        val admin = userService.createUser("admin-invalid-create-${UUID.randomUUID()}@example.com")
        val stranger = createActiveProfile(
            email = "admin-invalid-stranger-${UUID.randomUUID()}@example.com",
            displayName = "Stranger",
            gender = com.reals.backend.domain.Gender.MALE,
            lookingForGenders = setOf(com.reals.backend.domain.Gender.FEMALE)
        )

        mockMvc.perform(
            post("/api/admin/safety-reports")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "reportedUserId" to setup.userBId,
                            "reporterUserId" to setup.userBId,
                            "contextType" to "VISUAL_PROFILE",
                            "matchId" to setup.matchId,
                            "reason" to "OTHER",
                            "details" to "Invalid reporter"
                        )
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("SAFETY_REPORT_ADMIN_CREATE_INVALID")))

        mockMvc.perform(
            post("/api/admin/safety-reports")
                .with(authenticatedAsAdmin(admin.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "reportedUserId" to stranger,
                            "contextType" to "VISUAL_PROFILE",
                            "matchId" to setup.matchId,
                            "reason" to "OTHER",
                            "details" to "Context mismatch"
                        )
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("SAFETY_REPORT_ADMIN_CREATE_INVALID")))
    }

    @Test
    fun `admin list returns reduced summary without details verdict notes or raw user fields`() {
        val setup = createMatchWithFirstChat()
        chatExitService.cancelChatForSafety(
            chatId = setup.firstChatId,
            reporterUserId = setup.userAId,
            reason = ChatExitReason.HARASSMENT,
            details = "Sensitive report details"
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
            .andExpect(jsonPath("$[0].source", equalTo("USER")))
            .andExpect(jsonPath("$[0].status", equalTo("PENDING")))
            .andExpect(jsonPath("$[0].details").doesNotExist())
            .andExpect(jsonPath("$[0].verdictNotes").doesNotExist())
            .andExpect(jsonPath("$[0].reporter.email").doesNotExist())
            .andExpect(jsonPath("$[0].reporter.firebaseUid").doesNotExist())
            .andExpect(jsonPath("$[0].reported.email").doesNotExist())
            .andExpect(jsonPath("$[0].reported.firebaseUid").doesNotExist())
            .andExpect(jsonPath("$[0].reportedUserCounters.pendingReportsTotal", equalTo(1)))
    }

    @Test
    fun `admin detail includes details message evidence evidence snapshot and reduced users`() {
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
        val admin = userService.createUser("admin-report-detail-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/admin/safety-reports/${report.id}")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.report.id", equalTo(report.id.toString())))
            .andExpect(jsonPath("$.report.reason", equalTo("HARASSMENT")))
            .andExpect(jsonPath("$.report.status", equalTo("PENDING")))
            .andExpect(jsonPath("$.report.reporter.userId", equalTo(setup.userAId.toString())))
            .andExpect(jsonPath("$.report.reported.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.report.reported.email").doesNotExist())
            .andExpect(jsonPath("$.details", equalTo("Reported harassment")))
            .andExpect(jsonPath("$.messages.length()", equalTo(2)))
            .andExpect(jsonPath("$.messages[0].senderUserId", equalTo(setup.userAId.toString())))
            .andExpect(jsonPath("$.evidence.safetyReportId", equalTo(report.id.toString())))
            .andExpect(jsonPath("$.penalty").doesNotExist())
    }

    @Test
    fun `admin dismissal records audit event without verdict notes`() {
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
            .andExpect(jsonPath("$.penaltyId").doesNotExist())
            .andExpect(jsonPath("$.verdictNotes").doesNotExist())

        val updated = safetyReportRepository.findById(report.id).orElseThrow()
        assertEquals(SafetyReportStatus.DISMISSED, updated.status)
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))

        val audit = auditEventRepository.findAll()
            .single {
                it.eventType == AuditEventType.SAFETY_REPORT_DISMISSED &&
                    it.aggregateType == AuditAggregateType.SAFETY_REPORT &&
                    it.aggregateId == report.id
            }
        assertEquals(admin.id, audit.actorUserId)
        assertEquals(setup.userBId, audit.targetUserId)
        assertTrue(audit.metadataJson!!.contains("DISMISSED"))
        assertFalse(audit.metadataJson!!.contains("No se encontro falta suficiente."))
    }

    @Test
    fun `admin temporary penalty confirms report records audit and counters`() {
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
            .andExpect(jsonPath("$.reportedUserCounters.confirmedReportsTotal", equalTo(1)))
            .andExpect(jsonPath("$.reportedUserCounters.confirmedReportsLast30Days", equalTo(1)))

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

        val audit = auditEventRepository.findAll()
            .single {
                it.eventType == AuditEventType.SAFETY_REPORT_CONFIRMED &&
                    it.aggregateType == AuditAggregateType.SAFETY_REPORT &&
                    it.aggregateId == report.id
            }
        assertEquals(admin.id, audit.actorUserId)
        assertEquals(setup.userBId, audit.targetUserId)
        assertTrue(audit.metadataJson!!.contains("TEMPORARY_BAN"))
        assertFalse(audit.metadataJson!!.contains("Harassment confirmed"))
        assertFalse(audit.metadataJson!!.contains("Mensajes ofensivos confirmados."))
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
            .andExpect(jsonPath("$.code", equalTo("SAFETY_REPORT_ALREADY_REVIEWED")))
    }
}
