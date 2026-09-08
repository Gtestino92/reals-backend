package com.reals.backend.integration.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.PenaltyType
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SchedulingConcurrencyIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Value("\${account.ban.temporary-resume-margin-minutes:30}")
    private var temporaryResumeMarginMinutes: Long = 0

    @Value("\${chat.second-chat.entry-window-minutes:20}")
    private var secondChatEntryWindowMinutes: Long = 0

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `simultaneous submissions without overlap persist both lists and remain pending`() {
        val setup = TransactionTemplate(transactionManager).execute {
            createConnectionInSchedulingPhase()
        }
        val slot = futureHalfHourSlot()

        runConcurrently(
            {
                schedulingService.addProposals(
                    connectionId = setup.connectionId,
                    userId = setup.userAId,
                    expectedRoundNumber = 1,
                    proposedDateTimes = listOf(slot)
                )
            },
            {
                schedulingService.addProposals(
                    connectionId = setup.connectionId,
                    userId = setup.userBId,
                    expectedRoundNumber = 1,
                    proposedDateTimes = listOf(slot.plusHours(1))
                )
            }
        )

        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        val proposals = proposalRepository.findByConnectionId(setup.connectionId)

        assertEquals(NegotiationStatus.PENDING, negotiation.status)
        assertEquals(1, negotiation.roundNumber)
        assertEquals(2, proposals.size)
        assertTrue(proposals.all { it.status == ProposalStatus.PENDING })
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `simultaneous submissions with overlap confirm exactly once`() {
        val setup = TransactionTemplate(transactionManager).execute {
            createConnectionInSchedulingPhase()
        }
        val slot = futureHalfHourSlot()

        runConcurrently(
            {
                schedulingService.addProposals(
                    connectionId = setup.connectionId,
                    userId = setup.userAId,
                    expectedRoundNumber = 1,
                    proposedDateTimes = listOf(slot)
                )
            },
            {
                schedulingService.addProposals(
                    connectionId = setup.connectionId,
                    userId = setup.userBId,
                    expectedRoundNumber = 1,
                    proposedDateTimes = listOf(slot)
                )
            }
        )

        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        val proposals = proposalRepository.findByConnectionId(setup.connectionId)

        assertEquals(NegotiationStatus.CONFIRMED, negotiation.status)
        assertEquals(slot.toInstant(), negotiation.confirmedDateTime?.toInstant())
        assertEquals(2, proposals.count { it.status == ProposalStatus.ACCEPTED })
        assertEquals(ConnectionState.SECOND_CHAT_SCHEDULED, connectionService.findByIdOrThrow(setup.connectionId).state)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent reciprocal rejections advance the round once`() {
        val setup = TransactionTemplate(transactionManager).execute {
            val created = createConnectionInSchedulingPhase()
            val slot = futureHalfHourSlot()
            schedulingService.addProposals(
                connectionId = created.connectionId,
                userId = created.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(slot)
            )
            schedulingService.addProposals(
                connectionId = created.connectionId,
                userId = created.userBId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(slot.plusHours(1))
            )
            created
        }

        runConcurrently(
            {
                schedulingService.rejectPartnerProposals(
                    connectionId = setup.connectionId,
                    userId = setup.userAId,
                    expectedRoundNumber = 1
                )
            },
            {
                schedulingService.rejectPartnerProposals(
                    connectionId = setup.connectionId,
                    userId = setup.userBId,
                    expectedRoundNumber = 1
                )
            }
        )

        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        val proposals = proposalRepository.findByConnectionIdAndRoundNumber(setup.connectionId, 1)

        assertEquals(NegotiationStatus.PENDING, negotiation.status)
        assertEquals(2, negotiation.roundNumber)
        assertTrue(proposals.all { it.status == ProposalStatus.REJECTED })
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent acceptance and rejection leave one consistent winner`() {
        val setup = TransactionTemplate(transactionManager).execute {
            val created = createConnectionInSchedulingPhase()
            val proposal = schedulingService.addProposal(
                connectionId = created.connectionId,
                userId = created.userAId,
                proposedDateTime = futureHalfHourSlot(),
                expectedRoundNumber = 1
            )
            created to proposal.id
        }
        val (connectionSetup, proposalId) = setup

        val outcomes = runConcurrentlyCapturing(
            {
                schedulingService.acceptProposal(
                    connectionId = connectionSetup.connectionId,
                    proposalId = proposalId,
                    acceptorUserId = connectionSetup.userBId
                )
            },
            {
                schedulingService.rejectPartnerProposals(
                    connectionId = connectionSetup.connectionId,
                    userId = connectionSetup.userBId,
                    expectedRoundNumber = 1
                )
            }
        )

        assertEquals(1, outcomes.count { it == null })
        assertEquals(1, outcomes.filterNotNull().size)
        assertTrue(
            outcomes.filterNotNull().all {
                it is DomainConflictException &&
                    it.code in setOf(
                        DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE,
                        DomainErrorCode.SCHEDULING_PARTNER_PROPOSALS_NOT_AVAILABLE
                    )
            }
        )

        val negotiation = schedulingService.findNegotiationOrThrow(connectionSetup.connectionId)
        val proposals = proposalRepository.findByConnectionId(connectionSetup.connectionId)

        if (negotiation.status == NegotiationStatus.CONFIRMED) {
            assertNotNull(negotiation.confirmedDateTime)
            assertEquals(1, proposals.count { it.status == ProposalStatus.ACCEPTED })
            assertEquals(ConnectionState.SECOND_CHAT_SCHEDULED, connectionService.findByIdOrThrow(connectionSetup.connectionId).state)
        } else {
            assertEquals(NegotiationStatus.PENDING, negotiation.status)
            assertTrue(proposals.all { it.status == ProposalStatus.REJECTED })
            assertEquals(ConnectionState.SCHEDULING_PHASE, connectionService.findByIdOrThrow(connectionSetup.connectionId).state)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent explicit acceptance and temporary penalty cannot deadlock or leave invalid active scheduled slot`() {
        val setup = TransactionTemplate(transactionManager).execute {
            val created = createConnectionInSchedulingPhase()
            val slot = futureHalfHourSlot()
            val proposal = schedulingService.addProposal(
                connectionId = created.connectionId,
                userId = created.userAId,
                proposedDateTime = slot,
                expectedRoundNumber = 1
            )
            PenaltyRaceFixture(
                connection = created,
                proposalId = proposal.id,
                slot = slot
            )
        }
        val outcomes = runConcurrentlyCapturing(
            {
                schedulingService.acceptProposal(
                    connectionId = setup.connection.connectionId,
                    proposalId = setup.proposalId,
                    acceptorUserId = setup.connection.userBId
                )
            },
            {
                createIncompatibleTemporaryPenalty(
                    userId = setup.connection.userAId,
                    slot = setup.slot
                )
            }
        )

        assertNoUnexpectedRaceFailures(outcomes)
        assertInvalidBanExistsAndNoActiveInvalidScheduledSlot(setup)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent auto confirmation and temporary penalty cannot deadlock or leave invalid active scheduled slot`() {
        val setup = TransactionTemplate(transactionManager).execute {
            val created = createConnectionInSchedulingPhase()
            val slot = futureHalfHourSlot()
            schedulingService.addProposals(
                connectionId = created.connectionId,
                userId = created.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(slot)
            )
            PenaltyRaceFixture(
                connection = created,
                proposalId = UUID.randomUUID(),
                slot = slot
            )
        }
        val outcomes = runConcurrentlyCapturing(
            {
                schedulingService.addProposals(
                    connectionId = setup.connection.connectionId,
                    userId = setup.connection.userBId,
                    expectedRoundNumber = 1,
                    proposedDateTimes = listOf(setup.slot)
                )
            },
            {
                createIncompatibleTemporaryPenalty(
                    userId = setup.connection.userAId,
                    slot = setup.slot
                )
            }
        )

        assertNoUnexpectedRaceFailures(outcomes)
        assertInvalidBanExistsAndNoActiveInvalidScheduledSlot(setup)
    }

    private fun runConcurrently(
        first: () -> Any?,
        second: () -> Any?
    ) {
        val outcomes = runConcurrentlyCapturing(first, second)
        outcomes.filterNotNull().firstOrNull()?.let { throw it }
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

    private fun createIncompatibleTemporaryPenalty(
        userId: UUID,
        slot: OffsetDateTime
    ) {
        val penaltyNow = OffsetDateTime.now()
        val invalidBanExpiresAt =
            slot
                .plusMinutes(secondChatEntryWindowMinutes)
                .minusMinutes(temporaryResumeMarginMinutes)
                .plusSeconds(1)
        penaltyService.createTemporaryPenalty(
            userId = userId,
            reason = "Temporary ban racing with scheduling confirmation",
            duration = Duration.between(penaltyNow, invalidBanExpiresAt),
            now = penaltyNow
        )
    }

    private fun assertNoUnexpectedRaceFailures(outcomes: List<Throwable?>) {
        outcomes.filterNotNull().forEach { failure ->
            assertTrue(
                failure is DomainConflictException &&
                    failure.code == DomainErrorCode.SCHEDULING_SLOT_CONFLICT,
                "Unexpected concurrent scheduling failure: ${failure::class.qualifiedName} ${failure.message}"
            )
        }
    }

    private fun assertInvalidBanExistsAndNoActiveInvalidScheduledSlot(setup: PenaltyRaceFixture) {
        val effectiveBan = penaltyService.resolveEffectiveBan(setup.connection.userAId)
            ?: error("Expected effective temporary ban")
        val connection = connectionService.findByIdOrThrow(setup.connection.connectionId)

        assertEquals(PenaltyType.TEMPORARY_BAN, effectiveBan.type)
        assertTrue(
            requireNotNull(effectiveBan.expiresAt)
                .plusMinutes(temporaryResumeMarginMinutes)
                .isAfter(setup.slot.plusMinutes(secondChatEntryWindowMinutes))
        )
        assertFalse(
            connection.state in setOf(
                ConnectionState.SECOND_CHAT_SCHEDULED,
                ConnectionState.SECOND_CHAT_AVAILABLE,
                ConnectionState.SECOND_CHAT
            ),
            "Connection must not remain in an active confirmed second-chat state"
        )
    }

    private data class PenaltyRaceFixture(
        val connection: ConnectionFixture,
        val proposalId: UUID,
        val slot: OffsetDateTime
    )
}
