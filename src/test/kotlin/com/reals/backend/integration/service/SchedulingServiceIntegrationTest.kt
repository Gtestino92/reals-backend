package com.reals.backend.integration.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

class SchedulingServiceIntegrationTest : BaseIT() {

    @Test
    fun `add proposals rejects duplicate submission in same round`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot)
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PROPOSALS_ALREADY_SUBMITTED) {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(slot.plusHours(1))
            )
        }
    }

    @Test
    fun `add proposals rejects invalid slot count`() {
        val setup = createConnectionInSchedulingPhase()

        assertSchedulingCode(DomainErrorCode.SCHEDULING_INVALID_PROPOSALS) {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = emptyList()
            )
        }
    }

    @Test
    fun `add proposals rejects duplicate instants`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        assertSchedulingCode(DomainErrorCode.SCHEDULING_INVALID_PROPOSALS) {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(slot, slot)
            )
        }
    }

    @Test
    fun `add proposals rejects past slot`() {
        val setup = createConnectionInSchedulingPhase()

        assertSchedulingCode(DomainErrorCode.SCHEDULING_INVALID_PROPOSALS) {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(OffsetDateTime.now().minusDays(1).withMinute(0).withSecond(0).withNano(0))
            )
        }
    }

    @Test
    fun `add proposals rejects non half-hour aligned slot`() {
        val setup = createConnectionInSchedulingPhase()

        assertSchedulingCode(DomainErrorCode.SCHEDULING_INVALID_PROPOSALS) {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(futureHalfHourSlot().plusMinutes(15))
            )
        }
    }

    @Test
    fun `add proposals rejects connection outside scheduling phase`() {
        val setup = createConnectionInSchedulingPhase()
        val connection = connectionRepository.findById(setup.connectionId).orElseThrow()
        connection.state = ConnectionState.SECOND_CHAT_SCHEDULED
        connectionRepository.saveAndFlush(connection)

        assertSchedulingCode(DomainErrorCode.SCHEDULING_NOT_AVAILABLE) {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(futureHalfHourSlot())
            )
        }
    }

    @Test
    fun `add proposals rejects expired scheduling phase`() {
        val setup = createConnectionInSchedulingPhase()

        connectionRepository.updateSchedulingExpiresAt(
            connectionId = setup.connectionId,
            expiresAt = OffsetDateTime.now().minusSeconds(1)
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_EXPIRED) {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(futureHalfHourSlot())
            )
        }
    }

    @Test
    fun `add proposals rejects stale expected round`() {
        val setup = createConnectionInSchedulingPhase()
        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        negotiation.roundNumber = 2
        negotiationRepository.saveAndFlush(negotiation)

        assertSchedulingCode(DomainErrorCode.SCHEDULING_ROUND_CHANGED) {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(futureHalfHourSlot())
            )
        }
    }

    @Test
    fun `same round submission succeeds when partner proposals already exist`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot)
        )
        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot.plusHours(1))
        )

        val proposals = proposalRepository.findByConnectionId(setup.connectionId)
        assertEquals(2, proposals.size)
        assertTrue(proposals.all { it.roundNumber == 1 && it.status == ProposalStatus.PENDING })
        assertEquals(NegotiationStatus.PENDING, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
    }

    @Test
    fun `partner proposal rejection is independent and advances only after both lists rejected`() {
        val setup = createConnectionInSchedulingPhase()
        val slotA = futureHalfHourSlot()
        val slotB = slotA.plusHours(1)

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slotA)
        )

        val afterFirstRejection = schedulingService.rejectPartnerProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            expectedRoundNumber = 1
        )

        assertEquals(NegotiationStatus.PENDING, afterFirstRejection.status)
        assertEquals(1, afterFirstRejection.roundNumber)
        assertEquals(
            ProposalStatus.REJECTED,
            proposalRepository.findByConnectionId(setup.connectionId).single { it.userId == setup.userAId }.status
        )

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slotB)
        )

        val currentRoundBeforeSecondRejection =
            proposalRepository.findByConnectionIdAndRoundNumber(setup.connectionId, 1)
        assertEquals(
            ProposalStatus.PENDING,
            currentRoundBeforeSecondRejection.single { it.userId == setup.userBId }.status
        )

        val afterSecondRejection = schedulingService.rejectPartnerProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1
        )

        assertEquals(NegotiationStatus.PENDING, afterSecondRejection.status)
        assertEquals(2, afterSecondRejection.roundNumber)
        assertTrue(
            proposalRepository.findByConnectionIdAndRoundNumber(setup.connectionId, 1)
                .all { it.status == ProposalStatus.REJECTED }
        )
    }

    @Test
    fun `reject partner proposals rejects stale expected round`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot)
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_ROUND_CHANGED) {
            schedulingService.rejectPartnerProposals(
                connectionId = setup.connectionId,
                userId = setup.userBId,
                expectedRoundNumber = 2
            )
        }
    }

    @Test
    fun `accept proposal rejects own proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot(),
            expectedRoundNumber = 1
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_CANNOT_ACCEPT_OWN_PROPOSAL) {
            schedulingService.acceptProposal(
                connectionId = setup.connectionId,
                proposalId = proposal.id,
                acceptorUserId = setup.userAId
            )
        }
    }

    @Test
    fun `accept proposal rejects missing proposal`() {
        val setup = createConnectionInSchedulingPhase()

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE) {
            schedulingService.acceptProposal(
                connectionId = setup.connectionId,
                proposalId = UUID.randomUUID(),
                acceptorUserId = UUID.randomUUID()
            )
        }
    }

    @Test
    fun `accept proposal rejects proposal that is not pending`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot(),
            expectedRoundNumber = 1
        )

        schedulingService.acceptProposal(
            connectionId = setup.connectionId,
            proposalId = proposal.id,
            acceptorUserId = setup.userBId
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE) {
            schedulingService.acceptProposal(
                connectionId = setup.connectionId,
                proposalId = proposal.id,
                acceptorUserId = setup.userBId
            )
        }
    }

    @Test
    fun `accept proposal rejects proposal from old round`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot(),
            expectedRoundNumber = 1
        )
        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        negotiation.roundNumber += 1
        negotiationRepository.saveAndFlush(negotiation)

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE) {
            schedulingService.acceptProposal(
                connectionId = setup.connectionId,
                proposalId = proposal.id,
                acceptorUserId = setup.userBId
            )
        }
    }

    @Test
    fun `reject partner proposals fails when partner has no pending proposals`() {
        val setup = createConnectionInSchedulingPhase()

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(futureHalfHourSlot())
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PARTNER_PROPOSALS_NOT_AVAILABLE) {
            schedulingService.rejectPartnerProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1
            )
        }

        assertEquals(NegotiationStatus.PENDING, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionService.findByIdOrThrow(setup.connectionId).state)
    }

    @Test
    fun `retrying same partner proposal rejection returns stable conflict and preserves state`() {
        val setup = createConnectionInSchedulingPhase()

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(futureHalfHourSlot())
        )

        schedulingService.rejectPartnerProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            expectedRoundNumber = 1
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PARTNER_PROPOSALS_NOT_AVAILABLE) {
            schedulingService.rejectPartnerProposals(
                connectionId = setup.connectionId,
                userId = setup.userBId,
                expectedRoundNumber = 1
            )
        }

        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        assertEquals(NegotiationStatus.PENDING, negotiation.status)
        assertEquals(1, negotiation.roundNumber)
        assertTrue(
            proposalRepository.findByConnectionId(setup.connectionId)
                .all { it.status == ProposalStatus.REJECTED }
        )
    }

    @Test
    fun `rejected proposals cannot be accepted and do not auto confirm overlap`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        val rejectedProposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )

        schedulingService.rejectPartnerProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            expectedRoundNumber = 1
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE) {
            schedulingService.acceptProposal(
                connectionId = setup.connectionId,
                proposalId = rejectedProposal.id,
                acceptorUserId = setup.userBId
            )
        }

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot)
        )

        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        assertEquals(NegotiationStatus.PENDING, negotiation.status)
        assertEquals(1, negotiation.roundNumber)
        assertEquals(null, negotiation.confirmedDateTime)
        assertFalse(
            proposalRepository.findByConnectionId(setup.connectionId)
                .any { it.status == ProposalStatus.ACCEPTED }
        )
    }

    @Test
    fun `missing negotiation returns scheduling not found code`() {
        assertSchedulingCode(DomainErrorCode.SCHEDULING_NEGOTIATION_NOT_FOUND) {
            schedulingService.findNegotiationOrThrow(UUID.randomUUID())
        }
    }

    @Test
    fun `expire negotiation fails pending negotiation and closes connection once`() {
        val setup = createConnectionInSchedulingPhase()

        assertTrue(schedulingService.expireNegotiation(setup.connectionId))

        assertEquals(
            NegotiationStatus.FAILED,
            schedulingService.findNegotiationOrThrow(setup.connectionId).status
        )
        assertEquals(
            ConnectionState.CLOSED,
            connectionService.findByIdOrThrow(setup.connectionId).state
        )
        assertNoConnectionLocks(setup.userAId, setup.userBId)
        assertFalse(schedulingService.expireNegotiation(setup.connectionId))
    }

    private fun assertSchedulingCode(
        expected: DomainErrorCode,
        action: () -> Unit
    ) {
        val exception = assertThrows<DomainException> {
            action()
        }
        assertEquals(expected, exception.code)
    }
}
