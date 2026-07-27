package com.reals.backend.integration.controller

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestPropertySource(
    properties = [
        "scheduling.second-chat-conflict-window-minutes=60",
        "user-reliability.enabled=true"
    ]
)
class SchedulingConflictIntegrationTest : ControllerIT() {

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `proposal at exact lower conflict boundary is rejected`() {
        val fixture = TransactionTemplate(transactionManager).execute {
            createConflictFixture(confirmedAt = futureHalfHourSlot().plusDays(3))
        }

        assertSchedulingCode(DomainErrorCode.SCHEDULING_SLOT_CONFLICT) {
            TransactionTemplate(transactionManager).execute {
                schedulingService.addProposals(
                    connectionId = fixture.pendingConnectionId,
                    userId = fixture.sharedUserId,
                    expectedRoundNumber = 1,
                    proposedDateTimes = listOf(fixture.confirmedAt.minusMinutes(60))
                )
            }
        }

        assertNoProposals(fixture.pendingConnectionId)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `proposal at exact upper conflict boundary is rejected`() {
        val fixture = TransactionTemplate(transactionManager).execute {
            createConflictFixture(confirmedAt = futureHalfHourSlot().plusDays(3))
        }

        assertSchedulingCode(DomainErrorCode.SCHEDULING_SLOT_CONFLICT) {
            TransactionTemplate(transactionManager).execute {
                schedulingService.addProposals(
                    connectionId = fixture.pendingConnectionId,
                    userId = fixture.sharedUserId,
                    expectedRoundNumber = 1,
                    proposedDateTimes = listOf(fixture.confirmedAt.plusMinutes(60))
                )
            }
        }

        assertNoProposals(fixture.pendingConnectionId)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `proposal one half hour outside conflict window is accepted`() {
        val fixture = TransactionTemplate(transactionManager).execute {
            createConflictFixture(confirmedAt = futureHalfHourSlot().plusDays(3))
        }

        val saved = TransactionTemplate(transactionManager).execute {
            schedulingService.addProposals(
                connectionId = fixture.pendingConnectionId,
                userId = fixture.sharedUserId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(fixture.confirmedAt.minusMinutes(90))
            )
        }

        assertEquals(1, saved.size)
        assertEquals(ProposalStatus.PENDING, saved.single().status)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `explicit acceptance rechecks conflicts and keeps negotiation unchanged`() {
        val fixture = TransactionTemplate(transactionManager).execute {
            val created = createConflictFixture(
                confirmedAt = futureHalfHourSlot().plusDays(3),
                createConfirmedConflict = false
            )
            val proposal = schedulingService.addProposal(
                connectionId = created.pendingConnectionId,
                userId = created.pendingPartnerUserId,
                proposedDateTime = created.confirmedAt,
                expectedRoundNumber = 1
            )
            createConnection(
                userAId = created.sharedUserId,
                userBId = createUser("accept-conflict-partner"),
                state = ConnectionState.SECOND_CHAT_SCHEDULED,
                confirmedAt = created.confirmedAt
            )
            created.copy(proposalId = proposal.id)
        }

        assertSchedulingCode(DomainErrorCode.SCHEDULING_SLOT_CONFLICT) {
            TransactionTemplate(transactionManager).execute {
                schedulingService.acceptProposal(
                    connectionId = fixture.pendingConnectionId,
                    proposalId = fixture.proposalId!!,
                    acceptorUserId = fixture.sharedUserId
                )
            }
        }

        TransactionTemplate(transactionManager).execute {
            val negotiation = negotiationRepository.findByConnectionId(fixture.pendingConnectionId)
            val proposals = proposalRepository.findByConnectionId(fixture.pendingConnectionId)
            assertEquals(NegotiationStatus.PENDING, negotiation?.status)
            assertEquals(null, negotiation?.confirmedDateTime)
            assertTrue(proposals.all { it.status == ProposalStatus.PENDING })
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `auto confirmation skips conflicted overlap and confirms lower priority available overlap`() {
        val fixture = TransactionTemplate(transactionManager).execute {
            val created = createConflictFixture(
                confirmedAt = futureHalfHourSlot().plusDays(3),
                createConfirmedConflict = false
            )
            val availableAt = created.confirmedAt.plusMinutes(90)
            schedulingService.addProposals(
                connectionId = created.pendingConnectionId,
                userId = created.sharedUserId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(created.confirmedAt, availableAt)
            )
            createConnection(
                userAId = created.sharedUserId,
                userBId = createUser("auto-skip-conflict-partner"),
                state = ConnectionState.SECOND_CHAT_SCHEDULED,
                confirmedAt = created.confirmedAt
            )
            created.copy(availableAt = availableAt)
        }
        val availableAt = fixture.availableAt ?: error("availableAt fixture value is required")

        TransactionTemplate(transactionManager).execute {
            schedulingService.addProposals(
                connectionId = fixture.pendingConnectionId,
                userId = fixture.pendingPartnerUserId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(fixture.confirmedAt, availableAt)
            )
        }

        TransactionTemplate(transactionManager).execute {
            val negotiation = negotiationRepository.findByConnectionId(fixture.pendingConnectionId)
            assertEquals(NegotiationStatus.CONFIRMED, negotiation?.status)
            assertEquals(availableAt.toInstant(), negotiation?.confirmedDateTime?.toInstant())
            assertEquals(ConnectionState.SECOND_CHAT_SCHEDULED, connectionRepository.findById(fixture.pendingConnectionId).orElseThrow().state)
            assertEquals(
                2,
                proposalRepository.findByConnectionId(fixture.pendingConnectionId)
                    .count { it.status == ProposalStatus.ACCEPTED }
            )
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `auto confirmation with only conflicted overlaps rolls back triggering submission and reliability event`() {
        val fixture = TransactionTemplate(transactionManager).execute {
            val created = createConflictFixture(
                confirmedAt = futureHalfHourSlot().plusDays(3),
                createConfirmedConflict = false
            )
            schedulingService.addProposal(
                connectionId = created.pendingConnectionId,
                userId = created.sharedUserId,
                proposedDateTime = created.confirmedAt,
                expectedRoundNumber = 1
            )
            createConnection(
                userAId = created.sharedUserId,
                userBId = createUser("auto-rollback-conflict-partner"),
                state = ConnectionState.SECOND_CHAT_SCHEDULED,
                confirmedAt = created.confirmedAt
            )
            created
        }

        assertSchedulingCode(DomainErrorCode.SCHEDULING_SLOT_CONFLICT) {
            TransactionTemplate(transactionManager).execute {
                schedulingService.addProposal(
                    connectionId = fixture.pendingConnectionId,
                    userId = fixture.pendingPartnerUserId,
                    proposedDateTime = fixture.confirmedAt,
                    expectedRoundNumber = 1
                )
            }
        }

        TransactionTemplate(transactionManager).execute {
            val proposals = proposalRepository.findByConnectionId(fixture.pendingConnectionId)
            val negotiation = negotiationRepository.findByConnectionId(fixture.pendingConnectionId)
            assertEquals(1, proposals.size)
            assertEquals(fixture.sharedUserId, proposals.single().userId)
            assertEquals(NegotiationStatus.PENDING, negotiation?.status)
            assertFalse(
                userReliabilityEventRepository.existsByUserIdAndEventTypeAndRelatedConnectionId(
                    userId = fixture.pendingPartnerUserId,
                    eventType = UserReliabilityEventType.SCHEDULING_SLOTS_PROPOSED_ON_TIME,
                    relatedConnectionId = fixture.pendingConnectionId
                )
            )
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `availability endpoint returns sorted deduplicated current user windows only`() {
        val fixture = TransactionTemplate(transactionManager).execute {
            val sharedUserId = createUser("availability-shared")
            val currentPartnerId = createUser("availability-current")
            val firstAt = OffsetDateTime.parse("2030-01-10T20:00:00Z")
            val secondAt = OffsetDateTime.parse("2030-01-11T20:00:00Z")
            val omittedPastAt = OffsetDateTime.now().minusHours(3).withSecond(0).withNano(0)
            val currentConnectionId = createConnection(
                userAId = sharedUserId,
                userBId = currentPartnerId,
                state = ConnectionState.SCHEDULING_PHASE
            )

            createConnection(sharedUserId, createUser("availability-later"), ConnectionState.SECOND_CHAT_SCHEDULED, secondAt)
            createConnection(sharedUserId, createUser("availability-earlier"), ConnectionState.SECOND_CHAT_AVAILABLE, firstAt)
            createConnection(sharedUserId, createUser("availability-duplicate"), ConnectionState.SECOND_CHAT_SCHEDULED, firstAt)
            createConnection(sharedUserId, createUser("availability-second-chat"), ConnectionState.SECOND_CHAT, firstAt.plusHours(4))
            createConnection(sharedUserId, createUser("availability-closed"), ConnectionState.CLOSED, firstAt.plusHours(5))
            createConnection(sharedUserId, createUser("availability-past"), ConnectionState.SECOND_CHAT_SCHEDULED, omittedPastAt)
            createConnection(sharedUserId, createUser("availability-unconfirmed"), ConnectionState.SECOND_CHAT_SCHEDULED)
            createConnection(createUser("availability-other-user"), currentPartnerId, ConnectionState.SECOND_CHAT_SCHEDULED, firstAt.plusHours(6))

            AvailabilityFixture(
                sharedUserId = sharedUserId,
                currentConnectionId = currentConnectionId,
                firstAt = firstAt,
                secondAt = secondAt
            )
        }

        val response =
            mockMvc.perform(
                get("/api/connections/${fixture.currentConnectionId}/scheduling-availability")
                    .with(authenticatedAs(fixture.sharedUserId))
            )
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        val body = objectMapper.readTree(response)
        assertEquals(60, body["conflictWindowMinutes"].asInt())
        assertNotNull(body["serverTime"].asString())

        val windows = body["unavailableWindows"]
        assertEquals(2, windows.size())
        assertEquals(fixture.firstAt.minusMinutes(60).toInstant(), OffsetDateTime.parse(windows[0]["startsAt"].asString()).toInstant())
        assertEquals(fixture.firstAt.plusMinutes(60).toInstant(), OffsetDateTime.parse(windows[0]["endsAt"].asString()).toInstant())
        assertEquals(fixture.secondAt.minusMinutes(60).toInstant(), OffsetDateTime.parse(windows[1]["startsAt"].asString()).toInstant())
        assertEquals(fixture.secondAt.plusMinutes(60).toInstant(), OffsetDateTime.parse(windows[1]["endsAt"].asString()).toInstant())
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `simultaneous confirmations for different connections sharing a user do not both confirm conflicting slots`() {
        val fixture = TransactionTemplate(transactionManager).execute {
            val sharedUserId = createUser("concurrency-shared")
            val slot = futureHalfHourSlot().plusDays(3)
            val firstConnectionId = createConnection(sharedUserId, createUser("concurrency-first"), ConnectionState.SCHEDULING_PHASE)
            val secondConnectionId = createConnection(sharedUserId, createUser("concurrency-second"), ConnectionState.SCHEDULING_PHASE)
            val firstProposal = schedulingService.addProposal(
                connectionId = firstConnectionId,
                userId = connectionRepository.findById(firstConnectionId).orElseThrow().userBId,
                proposedDateTime = slot,
                expectedRoundNumber = 1
            )
            val secondProposal = schedulingService.addProposal(
                connectionId = secondConnectionId,
                userId = connectionRepository.findById(secondConnectionId).orElseThrow().userBId,
                proposedDateTime = slot,
                expectedRoundNumber = 1
            )

            ConcurrencyFixture(
                sharedUserId = sharedUserId,
                firstConnectionId = firstConnectionId,
                firstProposalId = firstProposal.id,
                secondConnectionId = secondConnectionId,
                secondProposalId = secondProposal.id
            )
        }

        val outcomes = runConcurrentlyCapturing(
            {
                schedulingService.acceptProposal(
                    connectionId = fixture.firstConnectionId,
                    proposalId = fixture.firstProposalId,
                    acceptorUserId = fixture.sharedUserId
                )
            },
            {
                schedulingService.acceptProposal(
                    connectionId = fixture.secondConnectionId,
                    proposalId = fixture.secondProposalId,
                    acceptorUserId = fixture.sharedUserId
                )
            }
        )

        assertEquals(1, outcomes.count { it == null })
        val conflict = outcomes.filterIsInstance<DomainConflictException>().single()
        assertEquals(DomainErrorCode.SCHEDULING_SLOT_CONFLICT, conflict.code)

        TransactionTemplate(transactionManager).execute {
            val negotiations =
                listOf(fixture.firstConnectionId, fixture.secondConnectionId)
                    .map { negotiationRepository.findByConnectionId(it)!! }
            assertEquals(1, negotiations.count { it.status == NegotiationStatus.CONFIRMED })
            assertEquals(1, negotiations.count { it.status == NegotiationStatus.PENDING })
        }
    }

    private fun assertSchedulingCode(
        expected: DomainErrorCode,
        action: () -> Unit
    ) {
        val exception = assertThrows<DomainConflictException> {
            action()
        }
        assertEquals(expected, exception.code)
    }

    private fun assertNoProposals(connectionId: UUID) {
        TransactionTemplate(transactionManager).execute {
            assertTrue(proposalRepository.findByConnectionId(connectionId).isEmpty())
        }
    }

    private fun createConflictFixture(
        confirmedAt: OffsetDateTime,
        createConfirmedConflict: Boolean = true
    ): ConflictFixture {
        val sharedUserId = createUser("conflict-shared")
        val pendingPartnerUserId = createUser("conflict-pending-partner")

        if (createConfirmedConflict) {
            createConnection(
                userAId = sharedUserId,
                userBId = createUser("conflict-confirmed-partner"),
                state = ConnectionState.SECOND_CHAT_SCHEDULED,
                confirmedAt = confirmedAt
            )
        }

        val pendingConnectionId = createConnection(
            userAId = sharedUserId,
            userBId = pendingPartnerUserId,
            state = ConnectionState.SCHEDULING_PHASE
        )

        return ConflictFixture(
            sharedUserId = sharedUserId,
            pendingPartnerUserId = pendingPartnerUserId,
            pendingConnectionId = pendingConnectionId,
            confirmedAt = confirmedAt
        )
    }

    private fun createUser(prefix: String): UUID =
        userService.createUser("$prefix-${UUID.randomUUID()}@example.com").id

    private fun createConnection(
        userAId: UUID,
        userBId: UUID,
        state: ConnectionState,
        confirmedAt: OffsetDateTime? = null
    ): UUID {
        val match =
            matchRepository.save(
                Match(
                    userAId = userAId,
                    userBId = userBId,
                    state = MatchState.VISUAL_APPROVED
                )
            )
        val now = OffsetDateTime.now()
        val connection =
            connectionRepository.save(
                Connection(
                    matchId = match.id,
                    userAId = userAId,
                    userBId = userBId,
                    state = state,
                    schedulingAvailableAt = now.minusMinutes(1),
                    schedulingExpiresAt = now.plusDays(30)
                )
            )
        negotiationRepository.save(
            ScheduleNegotiation(
                connectionId = connection.id,
                status = if (confirmedAt == null) NegotiationStatus.PENDING else NegotiationStatus.CONFIRMED,
                confirmedDateTime = confirmedAt
            )
        )
        return connection.id
    }

    private fun runConcurrentlyCapturing(
        first: () -> Any?,
        second: () -> Any?
    ): List<Throwable?> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = listOf(first, second).map { action ->
                executor.submit(
                    Callable<Throwable?> {
                        start.await()
                        try {
                            action()
                            null
                        } catch (ex: Throwable) {
                            ex
                        }
                    }
                )
            }

            start.countDown()

            return futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private data class ConflictFixture(
        val sharedUserId: UUID,
        val pendingPartnerUserId: UUID,
        val pendingConnectionId: UUID,
        val confirmedAt: OffsetDateTime,
        val proposalId: UUID? = null,
        val availableAt: OffsetDateTime? = null
    )

    private data class AvailabilityFixture(
        val sharedUserId: UUID,
        val currentConnectionId: UUID,
        val firstAt: OffsetDateTime,
        val secondAt: OffsetDateTime
    )

    private data class ConcurrencyFixture(
        val sharedUserId: UUID,
        val firstConnectionId: UUID,
        val firstProposalId: UUID,
        val secondConnectionId: UUID,
        val secondProposalId: UUID
    )
}
