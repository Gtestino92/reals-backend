package com.reals.backend.integration.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime

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

        assertThrows<IllegalStateException> {
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

        assertThrows<IllegalStateException> {
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

        assertThrows<IllegalStateException> {
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

        assertThrows<IllegalStateException> {
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

        assertThrows<IllegalStateException> {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                proposedDateTimes = listOf(futureHalfHourSlot().plusMinutes(15))
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

        assertThrows<IllegalStateException> {
            schedulingService.rejectCurrentRound(
                connectionId = setup.connectionId,
                userId = setup.userAId
            )
        }

        assertEquals(NegotiationStatus.PENDING, schedulingService.findNegotiationOrThrow(setup.connectionId).status)
        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionService.findByIdOrThrow(setup.connectionId).state)
    }
}
