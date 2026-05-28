package com.reals.backend.service

import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.domain.ScheduleProposal
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.ScheduleProposalRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class SchedulingService(
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val proposalRepository: ScheduleProposalRepository,
    private val connectionService: ConnectionService,
    /**
     * Maximum number of negotiation rounds before marking as FAILED.
     */
    @param:Value("\${scheduling.max-rounds:3}")
    private val maxRounds: Int

) {

    fun findNegotiationOrThrow(connectionId: UUID): ScheduleNegotiation {
        return negotiationRepository.findByConnectionId(connectionId)
            ?: throw NoSuchElementException(
                "ScheduleNegotiation not found for connection: $connectionId"
            )
    }

    fun findNegotiationOrNull(connectionId: UUID): ScheduleNegotiation? =
        negotiationRepository.findByConnectionId(connectionId)

    /**
     * Initializes the negotiation when a Connection enters SCHEDULING_PHASE.
     * Must be called exactly once per Connection.
     */
    fun initializeNegotiation(connectionId: UUID): ScheduleNegotiation {

        check(negotiationRepository.findByConnectionId(connectionId) == null) {
            "ScheduleNegotiation already exists for connection: $connectionId"
        }

        return negotiationRepository.save(
            ScheduleNegotiation(
                connectionId = connectionId
            )
        )
    }

    /**
     * Records an ordered list of second-chat slot proposals from a user.
     *
     * Rules:
     * - Negotiation must be in PENDING state.
     * - Each user can submit only one ordered list per round.
     * - The list must contain 1 to 3 unique future half-hour slots.
     * - After saving, auto-confirms if both users have at least one overlapping instant.
     */
    fun addProposals(
        connectionId: UUID,
        userId: UUID,
        proposedDateTimes: List<OffsetDateTime>
    ): List<ScheduleProposal> {

        val negotiation = findNegotiationOrThrow(connectionId)

        check(negotiation.status == NegotiationStatus.PENDING) {
            "Cannot add proposal: negotiation is ${negotiation.status}"
        }

        val connection = connectionService.findByIdOrThrow(connectionId)

        check(userId == connection.userAId || userId == connection.userBId) {
            "User $userId does not belong to connection $connectionId"
        }

        check(OffsetDateTime.now().isBefore(connection.schedulingExpiresAt)) {
            "Cannot add proposal: scheduling phase for connection $connectionId has expired"
        }

        validateProposalSlots(proposedDateTimes)

        check(
            !proposalRepository.existsByConnectionIdAndUserIdAndRoundNumber(
                connectionId,
                userId,
                negotiation.roundNumber
            )
        ) {
            "User $userId has already submitted proposals for round ${negotiation.roundNumber}."
        }

        val proposals = proposedDateTimes.mapIndexed { index, proposedDateTime ->
            ScheduleProposal(
                connectionId = connectionId,
                userId = userId,
                roundNumber = negotiation.roundNumber,
                preferenceOrder = index + 1,
                proposedDateTime = proposedDateTime
            )
        }

        val saved = proposalRepository.saveAll(proposals).toList()

        tryAutoConfirmOverlap(connectionId)

        return saved
    }

    fun addProposal(
        connectionId: UUID,
        userId: UUID,
        proposedDateTime: OffsetDateTime
    ): ScheduleProposal =
        addProposals(
            connectionId = connectionId,
            userId = userId,
            proposedDateTimes = listOf(proposedDateTime)
        ).first()

    /**
     * Auto-confirms if both users proposed at least one same instant (UTC comparison).
     * The agreed slot is chosen by the lowest combined preference order; ties choose
     * the earliest agreed instant.
     * For explicit acceptance use [acceptProposal].
     */
    private fun tryAutoConfirmOverlap(connectionId: UUID) {

        val negotiation = findNegotiationOrThrow(connectionId)

        if (negotiation.status != NegotiationStatus.PENDING) return

        val connection = connectionService.findByIdOrThrow(connectionId)

        val pending =
            proposalRepository.findByConnectionIdAndRoundNumber(
                connectionId,
                negotiation.roundNumber
            ).filter { it.status == ProposalStatus.PENDING }

        val byUser = pending.groupBy { it.userId }

        if (byUser.size < 2) return

        val proposalsA = byUser[connection.userAId].orEmpty()
        val proposalsB = byUser[connection.userBId].orEmpty()

        val overlap =
            proposalsA.flatMap { proposalA ->
                proposalsB
                    .filter { proposalB ->
                        proposalA.proposedDateTime.toInstant()
                            .equals(proposalB.proposedDateTime.toInstant())
                    }
                    .map { proposalB ->
                        AgreedSlotCandidate(
                            proposalA = proposalA,
                            proposalB = proposalB,
                            score = proposalA.preferenceOrder + proposalB.preferenceOrder
                        )
                    }
            }
                .minWithOrNull(
                    compareBy<AgreedSlotCandidate> { it.score }
                        .thenBy { it.proposalA.proposedDateTime.toInstant() }
                ) ?: return

        confirmWith(
            accepted = listOf(overlap.proposalA, overlap.proposalB),
            pending = pending,
            negotiation = negotiation,
            connectionId = connectionId
        )
    }

    /**
     * Explicitly accepts a partner's proposal.
     *
     * Rules:
     * - [acceptorUserId] must NOT be the proposer of [proposalId].
     * - [acceptorUserId] must have already submitted their own proposals this round.
     * - The proposal must be in PENDING state.
     * - Confirms the negotiation and transitions Connection to SECOND_CHAT_SCHEDULED.
     */
    fun acceptProposal(
        proposalId: UUID,
        acceptorUserId: UUID
    ): ScheduleNegotiation {

        val proposal = proposalRepository.findById(proposalId)
            .orElseThrow {
                NoSuchElementException("Proposal not found: $proposalId")
            }

        check(proposal.status == ProposalStatus.PENDING) {
            "Proposal $proposalId is not PENDING (current: ${proposal.status})"
        }

        check(proposal.userId != acceptorUserId) {
            "User $acceptorUserId cannot accept their own proposal"
        }

        check(
            proposalRepository.existsByConnectionIdAndUserIdAndRoundNumber(
                proposal.connectionId,
                acceptorUserId,
                proposal.roundNumber
            )
        ) {
            "User $acceptorUserId must submit their own proposals before accepting the partner's"
        }

        val connection = connectionService.findByIdOrThrow(proposal.connectionId)

        check(acceptorUserId == connection.userAId || acceptorUserId == connection.userBId) {
            "User $acceptorUserId does not belong to connection ${proposal.connectionId}"
        }

        check(OffsetDateTime.now().isBefore(connection.schedulingExpiresAt)) {
            "Cannot accept proposal: scheduling phase for connection ${proposal.connectionId} has expired"
        }

        val negotiation = findNegotiationOrThrow(proposal.connectionId)

        check(negotiation.status == NegotiationStatus.PENDING) {
            "Cannot accept proposal: negotiation is ${negotiation.status}"
        }

        check(proposal.roundNumber == negotiation.roundNumber) {
            "Proposal $proposalId belongs to round ${proposal.roundNumber}, current round is ${negotiation.roundNumber}"
        }

        val pending =
            proposalRepository.findByConnectionIdAndRoundNumber(
                proposal.connectionId,
                negotiation.roundNumber
            ).filter { it.status == ProposalStatus.PENDING }

        confirmWith(
            accepted = listOf(proposal),
            pending = pending,
            negotiation = negotiation,
            connectionId = proposal.connectionId
        )

        return negotiation
    }

    /**
     * Explicitly rejects the current negotiation round and opens the next one.
     *
     * Rules:
     * - Only callable if negotiation is PENDING.
     * - Both users must have submitted proposals in the current round.
     * - If maxRounds is exceeded, marks negotiation as FAILED and closes the connection.
     */
    fun rejectCurrentRound(
        connectionId: UUID,
        userId: UUID
    ): ScheduleNegotiation {

        val negotiation = findNegotiationOrThrow(connectionId)

        check(negotiation.status == NegotiationStatus.PENDING) {
            "Cannot reject round: negotiation is ${negotiation.status}"
        }

        val connection = connectionService.findByIdOrThrow(connectionId)

        check(userId == connection.userAId || userId == connection.userBId) {
            "User $userId does not belong to connection $connectionId"
        }

        val currentRoundProposals =
            proposalRepository.findByConnectionIdAndRoundNumber(
                connectionId,
                negotiation.roundNumber
            )

        val usersWithPendingProposals =
            currentRoundProposals
                .filter { it.status == ProposalStatus.PENDING }
                .map { it.userId }
                .toSet()

        check(
            usersWithPendingProposals.contains(connection.userAId) &&
                usersWithPendingProposals.contains(connection.userBId)
        ) {
            "Cannot reject round ${negotiation.roundNumber}: both users must submit proposals first"
        }

        currentRoundProposals
            .filter { it.status == ProposalStatus.PENDING }
            .forEach { it.status = ProposalStatus.REJECTED }

        proposalRepository.saveAll(currentRoundProposals)

        if (negotiation.roundNumber >= maxRounds) {
            negotiation.status = NegotiationStatus.FAILED
            negotiation.updatedAt = OffsetDateTime.now()

            negotiationRepository.save(negotiation)

            connectionService.closeConnection(connectionId)

            return negotiation
        }

        negotiation.roundNumber += 1
        negotiation.updatedAt = OffsetDateTime.now()

        return negotiationRepository.save(negotiation)
    }

    fun getProposals(connectionId: UUID): List<ScheduleProposal> =
        proposalRepository.findByConnectionId(connectionId)

    /**
     * Marks a PENDING negotiation as FAILED and closes its Connection.
     * Called by SchedulingNegotiationTimeoutJob when Connection.schedulingExpiresAt is past.
     */
    fun expireNegotiation(connectionId: UUID) {

        val negotiation = findNegotiationOrNull(connectionId)

        if (negotiation != null && negotiation.status == NegotiationStatus.PENDING) {
            negotiation.status = NegotiationStatus.FAILED
            negotiation.updatedAt = OffsetDateTime.now()

            negotiationRepository.save(negotiation)
        }

        connectionService.closeConnection(connectionId)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Marks [accepted] as ACCEPTED, rejects remaining PENDING proposals in the current round,
     * confirms the negotiation, and transitions the Connection to SECOND_CHAT_SCHEDULED.
     */
    private fun confirmWith(
        accepted: List<ScheduleProposal>,
        pending: List<ScheduleProposal>,
        negotiation: ScheduleNegotiation,
        connectionId: UUID
    ) {

        accepted.forEach { it.status = ProposalStatus.ACCEPTED }

        proposalRepository.saveAll(accepted)

        val acceptedIds = accepted.map { it.id }.toSet()

        pending
            .filter { it.id !in acceptedIds }
            .forEach { it.status = ProposalStatus.REJECTED }

        proposalRepository.saveAll(pending)

        negotiation.confirmedDateTime = accepted.minBy { it.preferenceOrder }.proposedDateTime
        negotiation.status = NegotiationStatus.CONFIRMED
        negotiation.updatedAt = OffsetDateTime.now()

        negotiationRepository.save(negotiation)

        connectionService.transitionToSecondChatScheduled(connectionId)
    }

    //TODO: ver si es la mejor manera de validar, con minutos o de otra manera
    private fun validateProposalSlots(proposedDateTimes: List<OffsetDateTime>) {
        check(proposedDateTimes.size in 1..3) { //TODO: que el maximo sea property, no hardcodeado
            "Proposal list must contain between 1 and 3 date/times"
        }

        val uniqueInstants = proposedDateTimes.map { it.toInstant() }.toSet()

        check(uniqueInstants.size == proposedDateTimes.size) {
            "Proposal list cannot contain duplicate date/times"
        }

        proposedDateTimes.forEach { proposedDateTime ->
            check(proposedDateTime.isAfter(OffsetDateTime.now())) {
                "Proposed date/time must be in the future"
            }

            check(
                (proposedDateTime.minute == 0 || proposedDateTime.minute == 30) &&
                    proposedDateTime.second == 0 &&
                    proposedDateTime.nano == 0
            ) {
                "Proposed date/time must be aligned to a half-hour boundary"
            }
        }
    }

    private data class AgreedSlotCandidate(
        val proposalA: ScheduleProposal,
        val proposalB: ScheduleProposal,
        val score: Int
    )
}
