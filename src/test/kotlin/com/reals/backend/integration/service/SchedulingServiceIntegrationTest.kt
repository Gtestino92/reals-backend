package com.reals.backend.integration.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
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
            proposedDateTimes = listOf(slot)
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PROPOSALS_ALREADY_SUBMITTED) {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
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
                proposedDateTimes = listOf(futureHalfHourSlot())
            )
        }
    }

    @Test
    fun `accept proposal rejects own proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot()
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_CANNOT_ACCEPT_OWN_PROPOSAL) {
            schedulingService.acceptProposal(
                proposalId = proposal.id,
                acceptorUserId = setup.userAId
            )
        }
    }

    @Test
    fun `accept proposal rejects missing proposal`() {
        assertSchedulingCode(DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE) {
            schedulingService.acceptProposal(
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
            proposedDateTime = futureHalfHourSlot()
        )

        schedulingService.acceptProposal(
            proposalId = proposal.id,
            acceptorUserId = setup.userBId
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE) {
            schedulingService.acceptProposal(
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
            proposedDateTime = futureHalfHourSlot()
        )
        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        negotiation.roundNumber += 1
        negotiationRepository.saveAndFlush(negotiation)

        assertSchedulingCode(DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE) {
            schedulingService.acceptProposal(
                proposalId = proposal.id,
                acceptorUserId = setup.userBId
            )
        }
    }

    @Test
    fun `reject current round fails unless both users submitted`() {
        val setup = createConnectionInSchedulingPhase()

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTimes = listOf(futureHalfHourSlot())
        )

        assertSchedulingCode(DomainErrorCode.SCHEDULING_ROUND_NOT_REJECTABLE) {
            schedulingService.rejectCurrentRound(
                connectionId = setup.connectionId,
                userId = setup.userAId
            )
        }

        assertEquals(NegotiationStatus.PENDING, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionService.findByIdOrThrow(setup.connectionId).state)
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
