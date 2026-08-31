package com.reals.backend.integration.postgres

import com.reals.backend.controller.dto.CreateSafetyReportRequest
import com.reals.backend.domain.Chat
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportSource
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SafetyReportPostgresIntegrationTest : PostgresITBase() {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent user report attempts converge to one report and one duplicate conflict`() {
        val fixture = TransactionTemplate(transactionManager).execute {
            val setup = createMatchWithFirstChat("pg-safety-report-concurrent")
            SafetyReportFixture(
                reporterUserId = setup.userAId,
                reportedUserId = setup.userBId,
                chat = chatRepository.findById(setup.firstChatId).orElseThrow()
            )
        }

        val outcomes = runReportCreationConcurrently(
            {
                val chat = chatRepository.findById(fixture.chat.id).orElseThrow()
                safetyReportService.createPendingReport(
                    chat = chat,
                    reporterUserId = fixture.reporterUserId,
                    reportedUserId = fixture.reportedUserId,
                    reason = com.reals.backend.domain.ChatExitReason.HARASSMENT,
                    details = "Concurrent safety report"
                )
            },
            {
                val chat = chatRepository.findById(fixture.chat.id).orElseThrow()
                safetyReportService.createPendingReport(
                    chat = chat,
                    reporterUserId = fixture.reporterUserId,
                    reportedUserId = fixture.reportedUserId,
                    reason = com.reals.backend.domain.ChatExitReason.HARASSMENT,
                    details = "Concurrent safety report"
                )
            }
        )

        assertEquals(1, outcomes.count { it.value != null }, outcomes.toString())
        val conflict = outcomes.mapNotNull { it.throwable }.single()
        assertTrue(conflict is DomainConflictException, outcomes.toString())
        assertEquals(DomainErrorCode.SAFETY_REPORT_ALREADY_EXISTS, (conflict as DomainConflictException).code)
        assertEquals(1, safetyReportRepository.findAll().size)
    }

    @Test
    fun `obsolete safety report context unique index is absent and user admin identities coexist`() {
        val obsoleteIndex = jdbcTemplate.queryForObject(
            "select to_regclass('uq_safety_report_reporter_reported_context')::text",
            String::class.java
        )
        assertNull(obsoleteIndex)

        val setup = createMatchInVisualPhase()
        safetyReportService.createUserReport(
            reporterUserId = setup.userAId,
            request = CreateSafetyReportRequest(
                reportedUserId = setup.userBId,
                contextType = SafetyReportContextType.VISUAL_PROFILE,
                matchId = setup.matchId,
                reason = SafetyReportReason.INAPPROPRIATE_BEHAVIOR,
                details = "User report before admin report"
            )
        )
        safetyReportRepository.saveAndFlush(
            SafetyReport(
                reporterUserId = setup.userAId,
                reportedUserId = setup.userBId,
                source = SafetyReportSource.ADMIN,
                createdByAdminUserId = UUID.randomUUID(),
                matchId = setup.matchId,
                contextType = SafetyReportContextType.VISUAL_PROFILE,
                contextId = setup.matchId,
                reason = SafetyReportReason.OTHER,
                details = "Admin report for the same context"
            )
        )

        val reports = safetyReportRepository.findAll()
        assertEquals(2, reports.size)
        assertEquals(
            setOf(SafetyReportSource.USER, SafetyReportSource.ADMIN),
            reports.map { it.source }.toSet()
        )
        assertEquals(
            "idx_safety_reports_reported_user",
            jdbcTemplate.queryForObject(
                "select to_regclass('idx_safety_reports_reported_user')::text",
                String::class.java
            )
        )
    }

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private fun runReportCreationConcurrently(
        vararg actions: () -> SafetyReport
    ): List<ConcurrentReportOutcome> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(actions.size)

        try {
            val futures = actions.map { action ->
                executor.submit(
                    Callable {
                        start.await()
                        try {
                            ConcurrentReportOutcome(value = action(), throwable = null)
                        } catch (ex: Throwable) {
                            ConcurrentReportOutcome(value = null, throwable = ex)
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

    private data class SafetyReportFixture(
        val reporterUserId: UUID,
        val reportedUserId: UUID,
        val chat: Chat
    )

    private data class ConcurrentReportOutcome(
        val value: SafetyReport?,
        val throwable: Throwable?
    )
}
