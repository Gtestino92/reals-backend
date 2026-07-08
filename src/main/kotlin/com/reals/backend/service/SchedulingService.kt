package com.reals.backend.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.domain.ScheduleProposal
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.ScheduleProposalRepository
import com.reals.backend.service.reliability.UserReliabilityScoreService
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
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
    private val userReliabilityScoreService: UserReliabilityScoreService,
    private val userBlockService: UserBlockService,
    /**
     * Maximum number of negotiation rounds before marking as FAILED.
     */
    @param:Value("\${scheduling.max-rounds:3}")
    private val maxRounds: Int,

    @param:Value("\${scheduling.max-proposals-per-round:3}")
    private val maxProposalsPerRound: Int

) {

    fun findNegotiationOrThrow(connectionId: UUID): ScheduleNegotiation {
        return negotiationRepository.findByConnectionId(connectionId)
            ?: throw DomainNotFoundException(
                code = DomainErrorCode.SCHEDULING_NEGOTIATION_NOT_FOUND,
                message = "Scheduling negotiation was not found"
            )
    }

    fun findNegotiationOrNull(connectionId: UUID): ScheduleNegotiation? =
        negotiationRepository.findByConnectionId(connectionId)

    /**
     * Atomically activates scheduling and creates the negotiation row. This keeps
     * the scheduler retry-safe if a previous run reached SCHEDULING_PHASE before
     * negotiation initialization completed.
     */
    fun activateSchedulingAndInitializeNegotiation(connectionId: UUID): ScheduleNegotiation {
        val connection =
            connectionService.activateScheduling(
                connectionId = connectionId
            )

        return initializeNegotiation(
            connectionId = connection.id
        )
    }

    /**
     * Initializes the negotiation when a Connection enters SCHEDULING_PHASE.
     * Must be called exactly once per Connection.
     */
    fun initializeNegotiation(connectionId: UUID): ScheduleNegotiation {

        negotiationRepository.findByConnectionId(connectionId)?.let { return it }

        val connection = connectionService.findByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)
        if (connection.state != ConnectionState.SCHEDULING_PHASE) {
            throw schedulingNotAvailable()
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
     * - The list must contain 1 to [maxProposalsPerRound] unique future half-hour slots.
     * - After saving, auto-confirms if both users have at least one overlapping instant.
     */
    fun addProposals(
        connectionId: UUID,
        userId: UUID,
        proposedDateTimes: List<OffsetDateTime>
    ): List<ScheduleProposal> {

        val connection = connectionService.findByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)

        val negotiation = findNegotiationOrThrow(connectionId)

        if (negotiation.status != NegotiationStatus.PENDING) {
            throw schedulingNotAvailable()
        }

        requireSchedulingPhase(connection.state)

        if (userId != connection.userAId && userId != connection.userBId) {
            throw AccessDeniedException("User $userId does not belong to connection $connectionId")
        }

        if (!OffsetDateTime.now().isBefore(connection.schedulingExpiresAt)) {
            throw schedulingExpired()
        }

        validateProposalSlots(proposedDateTimes)

        if (
            proposalRepository.existsByConnectionIdAndUserIdAndRoundNumber(
                connectionId,
                userId,
                negotiation.roundNumber
            )
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.SCHEDULING_PROPOSALS_ALREADY_SUBMITTED,
                message = "Scheduling proposals were already submitted for this round"
            )
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

        userReliabilityScoreService.recordEvent(
            userId = userId,
            eventType = UserReliabilityEventType.SCHEDULING_SLOTS_PROPOSED_ON_TIME,
            relatedMatchId = connection.matchId,
            relatedConnectionId = connectionId
        )

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
     * - The proposal must be in PENDING state.
     * - Confirms the negotiation and transitions Connection to SECOND_CHAT_SCHEDULED.
     */
    fun acceptProposal(
        proposalId: UUID,
        acceptorUserId: UUID
    ): ScheduleNegotiation {

        val proposal = proposalRepository.findById(proposalId)
            .orElseThrow {
                DomainNotFoundException(
                    code = DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE,
                    message = "Scheduling proposal was not found"
                )
            }

        if (proposal.status != ProposalStatus.PENDING) {
            throw proposalNotAvailable()
        }

        if (proposal.userId == acceptorUserId) {
            throw DomainConflictException(
                code = DomainErrorCode.SCHEDULING_CANNOT_ACCEPT_OWN_PROPOSAL,
                message = "Users cannot accept their own scheduling proposal"
            )
        }

        val connection = connectionService.findByIdOrThrow(proposal.connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)
        requireSchedulingPhase(connection.state)

        if (acceptorUserId != connection.userAId && acceptorUserId != connection.userBId) {
            throw AccessDeniedException(
                "User $acceptorUserId does not belong to connection ${proposal.connectionId}"
            )
        }

        if (!OffsetDateTime.now().isBefore(connection.schedulingExpiresAt)) {
            throw schedulingExpired()
        }

        val negotiation = findNegotiationOrThrow(proposal.connectionId)

        if (negotiation.status != NegotiationStatus.PENDING) {
            throw proposalNotAvailable()
        }

        if (proposal.roundNumber != negotiation.roundNumber) {
            throw proposalNotAvailable()
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

        if (negotiation.status != NegotiationStatus.PENDING) {
            throw roundNotRejectable()
        }

        val connection = connectionService.findByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)
        requireSchedulingPhase(connection.state)

        if (userId != connection.userAId && userId != connection.userBId) {
            throw AccessDeniedException("User $userId does not belong to connection $connectionId")
        }

        if (!OffsetDateTime.now().isBefore(connection.schedulingExpiresAt)) {
            throw schedulingExpired()
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

        if (
            !usersWithPendingProposals.contains(connection.userAId) ||
            !usersWithPendingProposals.contains(connection.userBId)
        ) {
            throw roundNotRejectable()
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
    fun expireNegotiation(connectionId: UUID): Boolean {
        val connection = connectionService.findByIdOrThrow(connectionId)
        if (connection.state != ConnectionState.SCHEDULING_PHASE) {
            return false
        }

        val negotiation = findNegotiationOrNull(connectionId)
        if (negotiation != null && negotiation.status == NegotiationStatus.PENDING) {
            val usersWithAnyProposal =
                proposalRepository.findByConnectionId(connectionId)
                    .map { it.userId }
                    .toSet()

            listOf(connection.userAId, connection.userBId)
                .filter { it !in usersWithAnyProposal }
                .forEach { userId ->
                    userReliabilityScoreService.recordEvent(
                        userId = userId,
                        eventType = UserReliabilityEventType.SCHEDULING_EXPIRED_NO_PROPOSAL,
                        relatedMatchId = connection.matchId,
                        relatedConnectionId = connectionId
                    )
                }

            negotiation.status = NegotiationStatus.FAILED
            negotiation.updatedAt = OffsetDateTime.now()

            negotiationRepository.save(negotiation)
        }

        return connectionService.closeConnection(connectionId)
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
        val connection = connectionService.findByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)

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

    private fun requireSchedulingPhase(state: ConnectionState) {
        if (state != ConnectionState.SCHEDULING_PHASE) {
            throw schedulingNotAvailable()
        }
    }

    private fun validateProposalSlots(proposedDateTimes: List<OffsetDateTime>) {
        if (proposedDateTimes.size !in 1..maxProposalsPerRound) {
            throw invalidProposals()
        }

        val uniqueInstants = proposedDateTimes.map { it.toInstant() }.toSet()

        if (uniqueInstants.size != proposedDateTimes.size) {
            throw invalidProposals()
        }

        proposedDateTimes.forEach { proposedDateTime ->
            if (!proposedDateTime.isAfter(OffsetDateTime.now())) {
                throw invalidProposals()
            }

            if (
                (proposedDateTime.minute != 0 && proposedDateTime.minute != 30) ||
                proposedDateTime.second != 0 ||
                proposedDateTime.nano != 0
            ) {
                throw invalidProposals()
            }
        }
    }

    private fun schedulingNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SCHEDULING_NOT_AVAILABLE,
            message = "Scheduling is not available"
        )

    private fun schedulingExpired(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SCHEDULING_EXPIRED,
            message = "Scheduling has expired"
        )

    private fun invalidProposals(): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.SCHEDULING_INVALID_PROPOSALS,
            message = "Scheduling proposals are invalid"
        )

    private fun proposalNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SCHEDULING_PROPOSAL_NOT_AVAILABLE,
            message = "Scheduling proposal is not available"
        )

    private fun roundNotRejectable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SCHEDULING_ROUND_NOT_REJECTABLE,
            message = "Scheduling round is not rejectable"
        )

    private data class AgreedSlotCandidate(
        val proposalA: ScheduleProposal,
        val proposalB: ScheduleProposal,
        val score: Int
    )
}
