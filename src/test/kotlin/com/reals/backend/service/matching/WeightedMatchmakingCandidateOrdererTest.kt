package com.reals.backend.service.matching

import com.reals.backend.domain.MatchmakingCandidatePair
import com.reals.backend.domain.MatchmakingPartnerCandidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class WeightedMatchmakingCandidateOrdererTest {

    private val now = OffsetDateTime.parse("2026-07-14T12:00:00Z")

    @Test
    fun `weighted ordering returns every candidate exactly once`() {
        val candidates =
            listOf(
                weighted("a", logWeight = 0.0, order = 0),
                weighted("b", logWeight = -1.0, order = 1),
                weighted("c", logWeight = -2.0, order = 2)
            )

        val ordered = orderer(0.4, 0.5, 0.6).order(candidates)

        assertEquals(3, ordered.size)
        assertEquals(candidates.map { it.candidate.partnerQueueEntryId }.toSet(), ordered.map { it.candidate.partnerQueueEntryId }.toSet())
        assertEquals(3, ordered.map { it.candidate.partnerQueueEntryId }.distinct().size)
    }

    @Test
    fun `priority uses log weight plus gumbel noise`() {
        val higherWeight = weighted("higher", logWeight = 0.0, order = 0)
        val lowerWeight = weighted("lower", logWeight = -10.0, order = 1)

        val ordered = orderer(0.5, 0.5).order(listOf(lowerWeight, higherWeight))

        assertEquals(higherWeight.candidate.partnerQueueEntryId, ordered.first().candidate.partnerQueueEntryId)
    }

    @Test
    fun `fifo order is final tie break when priorities are equal`() {
        val first = weighted("first", logWeight = 0.0, order = 0)
        val second = weighted("second", logWeight = 0.0, order = 1)

        val ordered = orderer(0.5, 0.5).order(listOf(second, first))

        assertEquals(first.candidate.partnerQueueEntryId, ordered.first().candidate.partnerQueueEntryId)
    }

    @Test
    fun `controlled sequence can place higher weight candidate first`() {
        val higherWeight = weighted("higher", logWeight = 0.0, order = 0)
        val lowerWeight = weighted("lower", logWeight = -1.0, order = 1)

        val ordered = orderer(0.8, 0.2).order(listOf(higherWeight, lowerWeight))

        assertEquals(higherWeight.candidate.partnerQueueEntryId, ordered.first().candidate.partnerQueueEntryId)
    }

    @Test
    fun `controlled sequence can place lower weight candidate first`() {
        val higherWeight = weighted("higher", logWeight = 0.0, order = 0)
        val lowerWeight = weighted("lower", logWeight = -1.0, order = 1)

        val ordered = orderer(0.2, 0.99).order(listOf(higherWeight, lowerWeight))

        assertEquals(lowerWeight.candidate.partnerQueueEntryId, ordered.first().candidate.partnerQueueEntryId)
    }

    @Test
    fun `ordered candidates are a complete fallback permutation`() {
        val candidates =
            listOf(
                weighted("a", logWeight = 0.0, order = 0),
                weighted("b", logWeight = 0.0, order = 1),
                weighted("c", logWeight = -1.0, order = 2)
            )
        val ordered =
            orderer(0.1, 0.2)
                .order(candidates)

        assertEquals(candidates.size, ordered.size)
        assertEquals(
            candidates.map { it.candidate.partnerQueueEntryId }.toSet(),
            ordered.map { it.candidate.partnerQueueEntryId }.toSet()
        )
        assertEquals(
            ordered.size,
            ordered.map { it.candidate.partnerQueueEntryId }.distinct().size
        )
    }

    private fun orderer(vararg values: Double): WeightedMatchmakingCandidateOrderer {
        val iterator = values.iterator()
        return WeightedMatchmakingCandidateOrderer {
            if (iterator.hasNext()) {
                iterator.nextDouble()
            } else {
                0.5
            }
        }
    }

    private fun weighted(
        label: String,
        logWeight: Double,
        order: Int
    ): WeightedMatchmakingPartnerCandidate =
        WeightedMatchmakingPartnerCandidate(
            candidate = candidate(label),
            logWeight = logWeight,
            order = order
        )

    private fun candidate(label: String): MatchmakingPartnerCandidate {
        val partnerId = UUID.nameUUIDFromBytes(label.toByteArray())
        return MatchmakingPartnerCandidate(
            partnerQueueEntryId = partnerId,
            partnerEnteredAt = now,
            pair = MatchmakingCandidatePair(
                userAId = UUID.nameUUIDFromBytes("anchor".toByteArray()),
                userBId = partnerId,
                userALatitude = -34.6037,
                userALongitude = -58.3816,
                userBLatitude = -34.6037,
                userBLongitude = -58.3816
            )
        )
    }
}
