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
    @Value("\${scheduling.max-rounds:3}")
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
     * Records a time slot proposal from a user.
     *
     * Rules:
     * - Negotiation must be in PENDING state.
     * - Each user can submit only one proposal per round.
     * - After saving, auto-confirms if both users proposed the exact same instant.
     */
    fun addProposal(
        connectionId: UUID,
        userId: UUID,
        proposedDateTime: OffsetDateTime
    ): ScheduleProposal {

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

        check(proposedDateTime.isAfter(OffsetDateTime.now())) {
            "Proposed date/time must be in the future"
        }

        check(!proposalRepository.existsByConnectionIdAndUserId(connectionId, userId)) {
            "User $userId has already submitted a proposal for this round. Open a new round to propose again."
        }

        val proposal = proposalRepository.save(
            ScheduleProposal(
                connectionId = connectionId,
                userId = userId,
                proposedDateTime = proposedDateTime
            )
        )

        tryAutoConfirmExact(connectionId)

        return proposal
    }

    /**
     * Auto-confirms if both users proposed the exact same instant (UTC comparison).
     * Only runs immediately after a new proposal is added.
     * For explicit acceptance use [acceptProposal].
     */
    private fun tryAutoConfirmExact(connectionId: UUID) {

        val negotiation = findNegotiationOrThrow(connectionId)

        if (negotiation.status != NegotiationStatus.PENDING) return

        val connection = connectionService.findByIdOrThrow(connectionId)

        val pending =
            proposalRepository.findByConnectionIdAndStatus(
                connectionId,
                ProposalStatus.PENDING
            )

        val byUser = pending.groupBy { it.userId }

        if (byUser.size < 2) return

        val proposalA = byUser[connection.userAId]?.firstOrNull() ?: return
        val proposalB = byUser[connection.userBId]?.firstOrNull() ?: return

        if (!proposalA.proposedDateTime.toInstant()
                .equals(proposalB.proposedDateTime.toInstant())
        ) return
        
        confirmWith(
            accepted = proposalA,
            acceptorProposal = proposalB,
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
     * - [acceptorUserId] must have already submitted their own proposal this round.
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
            proposalRepository.existsByConnectionIdAndUserId(
                proposal.connectionId,
                acceptorUserId
            )
        ) {
            "User $acceptorUserId must submit their own proposal before accepting the partner's"
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

        val pending =
            proposalRepository.findByConnectionIdAndStatus(
                proposal.connectionId,
                ProposalStatus.PENDING
            )

        val acceptorProposal =
            pending.first { it.userId == acceptorUserId }

        confirmWith(
            accepted = proposal,
            acceptorProposal = acceptorProposal,
            pending = pending,
            negotiation = negotiation,
            connectionId = proposal.connectionId
        )

        return negotiation
    }

    /**
     * Opens a new negotiation round when both users have submitted proposals
     * but no overlap was found.
     *
     * Rules:
     * - Only callable if negotiation is PENDING and current proposals are exhausted.
     * - If maxRounds is exceeded, marks negotiation as FAILED and closes the connection.
     */
    fun openNewRound(connectionId: UUID): ScheduleNegotiation {

        val negotiation = findNegotiationOrThrow(connectionId)

        check(negotiation.status == NegotiationStatus.PENDING) {
            "Cannot open new round: negotiation is ${negotiation.status}"
        }

        if (negotiation.roundNumber >= maxRounds) {
            negotiation.status = NegotiationStatus.FAILED
            negotiation.updatedAt = OffsetDateTime.now()

            negotiationRepository.save(negotiation)

            connectionService.closeConnection(connectionId)

            return negotiation
        }

        // Clear pending proposals and increment round
        proposalRepository.deleteByConnectionId(connectionId)

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
     * Marks [accepted] and [acceptorProposal] as ACCEPTED, rejects remaining PENDING proposals,
     * confirms the negotiation, and transitions the Connection to SECOND_CHAT_SCHEDULED.
     * [accepted] is the proposal being accepted; [acceptorProposal] is the acceptor's own proposal.
     */
    private fun confirmWith(
        accepted: ScheduleProposal,
        acceptorProposal: ScheduleProposal,
        pending: List<ScheduleProposal>,
        negotiation: ScheduleNegotiation,
        connectionId: UUID
    ) {

        accepted.status = ProposalStatus.ACCEPTED
        acceptorProposal.status = ProposalStatus.ACCEPTED

        proposalRepository.save(accepted)
        proposalRepository.save(acceptorProposal)

        pending
            .filter { it.id != accepted.id && it.id != acceptorProposal.id }
            .forEach { it.status = ProposalStatus.REJECTED }

        proposalRepository.saveAll(pending)

        negotiation.confirmedDateTime = accepted.proposedDateTime
        negotiation.status = NegotiationStatus.CONFIRMED
        negotiation.updatedAt = OffsetDateTime.now()

        negotiationRepository.save(negotiation)

        connectionService.transitionToSecondChatScheduled(connectionId)
    }
}
