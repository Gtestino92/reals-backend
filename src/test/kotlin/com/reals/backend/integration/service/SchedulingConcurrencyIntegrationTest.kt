package com.reals.backend.integration.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SchedulingConcurrencyIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

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
}
