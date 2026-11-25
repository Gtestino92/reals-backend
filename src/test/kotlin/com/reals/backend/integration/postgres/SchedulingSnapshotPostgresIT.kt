package com.reals.backend.integration.postgres

import com.google.firebase.auth.FirebaseToken
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.authentication.FirebaseTokenAuthenticationVerifier
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.domain.*
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.SchedulingSnapshotService
import com.reals.backend.service.UserService
import org.hibernate.resource.jdbc.spi.StatementInspector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneOffset
import java.time.OffsetDateTime
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SnapshotSqlInspector : StatementInspector {
    companion object {
        val observer = ThreadLocal<(String) -> Unit>()
    }
    override fun inspect(sql: String): String {
        observer.get()?.invoke(sql)
        return sql
    }
}

@AutoConfigureMockMvc
@Import(SchedulingSnapshotPostgresIT.AuthConfig::class)
@TestPropertySource(properties = [
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.reals.backend.integration.postgres.SnapshotSqlInspector"
])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulingSnapshotPostgresIT : PostgresITBase() {
    @TestConfiguration
    class AuthConfig {
        @Bean
        fun tokenFilter(policy: EnvironmentExposurePolicy, users: UserService, penalties: PenaltyService,
                        verifier: FirebaseTokenAuthenticationVerifier) = FirebaseTokenFilter(policy, users, penalties, verifier)
    }

    @MockitoBean private lateinit var clock: Clock
    @org.junit.jupiter.api.BeforeEach
    fun configureClock() {
        Mockito.`when`(clock.zone).thenReturn(ZoneOffset.UTC)
        Mockito.`when`(clock.instant()).thenAnswer { java.time.Instant.now() }
    }

    @MockitoBean private lateinit var verifier: FirebaseTokenAuthenticationVerifier
    @Autowired private lateinit var snapshots: SchedulingSnapshotService
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var manager: PlatformTransactionManager

    private fun identity(id: UUID) {
        val user = userRepository.findById(id).orElseThrow()
        user.firebaseUid = id.toString()
        user.authOrigin = UserAuthOrigin.EMAIL_PASSWORD
        userRepository.save(user)
        val token = Mockito.mock(FirebaseToken::class.java)
        Mockito.`when`(token.uid).thenReturn(id.toString())
        Mockito.`when`(token.claims).thenReturn(mapOf("firebase" to mapOf("sign_in_provider" to "password")))
        Mockito.`when`(verifier.verify(id.toString())).thenReturn(token)
    }

    private fun read(setup: ConnectionFixture, suffix: String, user: UUID = setup.userAId, status: Int = 200): tools.jackson.databind.JsonNode {
        val response = mvc.perform(get("/api/connections/${setup.connectionId}$suffix")
            .header("Authorization", "Bearer $user")).andReturn().response
        assertEquals(status, response.status, response.contentAsString)
        if (suffix == "/scheduling-snapshot" && status == 200) assertEquals("no-store", response.getHeader("Cache-Control"))
        return mapper.readTree(response.contentAsByteArray)
    }

    @Test
    fun authenticatedCycle() {
        val setup = TransactionTemplate(manager).execute { createConnectionInSchedulingPhase() }!!
        identity(setup.userAId)
        val sql = mutableListOf<String>()
        SnapshotSqlInspector.observer.set { sql.add(it) }
        try {
            val connection = read(setup, "")
            val negotiation = read(setup, "/negotiation")
            val proposals = read(setup, "/proposals")
            val availability = read(setup, "/scheduling-availability")
            read(setup, "/negotiation")
            read(setup, "")
            val baseline = sql.size
            sql.clear()
            val snapshot = read(setup, "/scheduling-snapshot")
            println("Scheduling authenticated HTTP statements: six=$baseline snapshot=${sql.size}")
            assertTrue(sql.size * 2 < baseline)
            assertTrue(sql.all { it.startsWith("select", ignoreCase = true) && !it.contains("for update", true) })
            assertEquals(connection, snapshot["connection"])
            assertEquals(negotiation, snapshot["negotiation"])
            assertEquals(proposals, snapshot["proposals"])
            assertEquals(availability["unavailableWindows"], snapshot["availability"]["unavailableWindows"])
            assertEquals(snapshot["serverTime"], snapshot["availability"]["serverTime"])
            sql.clear()
            snapshots.read(setup.connectionId, setup.userAId)
            println("Scheduling domain-only snapshot statements: ${sql.size}")
            assertEquals(4, sql.size)
        } finally {
            SnapshotSqlInspector.observer.remove()
        }

        val outsider = userService.createUser("outsider@example.invalid").id
        identity(outsider)
        read(setup, "/scheduling-snapshot", outsider, 403)
        userBlockCommandService.blockUserAndContain(setup.userAId, setup.userBId, UserBlockSource.MANUAL)
        assertEquals("CLOSED", read(setup, "/scheduling-snapshot")["connection"]["state"].asString())
        penaltyService.createTemporaryPenalty(setup.userAId, "fixture", Duration.ofHours(1))
        read(setup, "/scheduling-snapshot", status = 403)
    }

    @Test
    fun pendingAndDeadlineBoundaries() {
        val setup = TransactionTemplate(manager).execute { createConnectionInSchedulingPhase() }!!
        identity(setup.userAId)
        val connection = connectionRepository.findById(setup.connectionId).orElseThrow()
        val deadline = connection.schedulingExpiresAt
        for (delta in listOf(-1L, 0L, 1L)) {
            Mockito.`when`(clock.instant()).thenReturn(deadline.toInstant().plusNanos(delta))
            val result = read(setup, "/scheduling-snapshot")
            assertEquals("PENDING", result["negotiation"]["status"].asString())
            assertEquals(deadline.toInstant(), OffsetDateTime.parse(result["negotiation"]["schedulingExpiresAt"].asString()).toInstant())
        }
        negotiationRepository.delete(schedulingService.findNegotiationOrThrow(setup.connectionId))
        assertEquals("SCHEDULING_NEGOTIATION_NOT_FOUND", read(setup, "/scheduling-snapshot", status = 404)["code"].asString())
        connection.state = ConnectionState.SCHEDULING_PENDING
        connectionRepository.save(connection)
        val sql = mutableListOf<String>()
        SnapshotSqlInspector.observer.set { sql.add(it) }
        try {
            val result = read(setup, "/scheduling-snapshot")
            assertTrue(result["negotiation"].isNull)
            assertTrue(result["availability"].isNull)
            assertEquals(0, result["proposals"].size())
            assertFalse(sql.any { it.contains("schedule_negotiations") || it.contains("schedule_proposals") })
        } finally {
            SnapshotSqlInspector.observer.remove()
        }
    }

    @Test
    fun concurrentCommitsRemainInvisibleUntilNextRead() {
        val setup = TransactionTemplate(manager).execute { createConnectionInSchedulingPhase() }!!
        identity(setup.userAId)
        val slot = futureHalfHourSlot()
        fun duringRead(write: () -> Unit): tools.jackson.databind.JsonNode {
            var triggered = false
            SnapshotSqlInspector.observer.set { sql ->
                if (!triggered && sql.contains("from schedule_negotiations")) {
                    triggered = true
                    assertEquals("repeatable read", jdbcTemplate.queryForObject("show transaction_isolation", String::class.java))
                    assertEquals("on", jdbcTemplate.queryForObject("show transaction_read_only", String::class.java))
                    CompletableFuture.runAsync { TransactionTemplate(manager).executeWithoutResult { write() } }
                        .get(10, TimeUnit.SECONDS)
                }
            }
            try {
                return read(setup, "/scheduling-snapshot").also { assertTrue(triggered) }
            } finally {
                SnapshotSqlInspector.observer.remove()
            }
        }
        val partner = userService.createUser("other-partner@example.invalid").id
        val otherSlot = slot.plusDays(1)
        val beforeOtherConfirmation = duringRead {
            val match = matchRepository.save(Match(userAId = setup.userAId, userBId = partner, state = MatchState.VISUAL_APPROVED))
            val connection = connectionRepository.save(Connection(matchId = match.id, userAId = setup.userAId,
                userBId = partner, state = ConnectionState.SECOND_CHAT_SCHEDULED, schedulingExpiresAt = otherSlot))
            negotiationRepository.save(ScheduleNegotiation(connectionId = connection.id, status = NegotiationStatus.CONFIRMED,
                confirmedDateTime = otherSlot))
        }
        assertEquals(0, beforeOtherConfirmation["availability"]["unavailableWindows"].size())
        val windowEnd = otherSlot.plusHours(1).toInstant()
        for (delta in listOf(-1L, 0L, 1L)) {
            Mockito.`when`(clock.instant()).thenReturn(windowEnd.plusNanos(delta))
            val result = read(setup, "/scheduling-snapshot")
            assertEquals(if (delta <= 0) 1 else 0, result["availability"]["unavailableWindows"].size())
        }
        Mockito.`when`(clock.instant()).thenAnswer { java.time.Instant.now() }
        val beforeRound = duringRead {
            schedulingService.addProposal(setup.connectionId, setup.userAId, slot, 1)
            schedulingService.rejectPartnerProposals(setup.connectionId, setup.userBId, 1)
            schedulingService.addProposal(setup.connectionId, setup.userBId, slot, 1)
            schedulingService.rejectPartnerProposals(setup.connectionId, setup.userAId, 1)
        }
        assertEquals(1, beforeRound["availability"]["unavailableWindows"].size())
        assertEquals(1, beforeRound["negotiation"]["roundNumber"].asInt())
        assertEquals(0, beforeRound["proposals"].size())
        val beforeConfirmation = duringRead {
            schedulingService.addProposal(setup.connectionId, setup.userAId, slot, 2)
            schedulingService.addProposal(setup.connectionId, setup.userBId, slot, 2)
        }
        assertEquals(2, beforeConfirmation["negotiation"]["roundNumber"].asInt())
        assertEquals("PENDING", beforeConfirmation["negotiation"]["status"].asString())
        assertEquals(2, beforeConfirmation["proposals"].size())
        val beforeClosure = duringRead {
            userBlockCommandService.blockUserAndContain(setup.userAId, setup.userBId, UserBlockSource.MANUAL)
        }
        assertEquals("SECOND_CHAT_SCHEDULED", beforeClosure["connection"]["state"].asString())
        assertEquals("CONFIRMED", beforeClosure["negotiation"]["status"].asString())
        assertEquals("CLOSED", read(setup, "/scheduling-snapshot")["connection"]["state"].asString())
    }
}
