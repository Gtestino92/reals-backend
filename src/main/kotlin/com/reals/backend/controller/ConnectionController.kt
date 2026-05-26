package com.reals.backend.controller

import com.reals.backend.config.CurrentUserId
import com.reals.backend.controller.dto.AddProposalRequest
import com.reals.backend.controller.dto.ConnectionResponse
import com.reals.backend.controller.dto.NegotiationResponse
import com.reals.backend.controller.dto.ScheduleProposalResponse
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.SchedulingService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/connections")
class ConnectionController(
    private val connectionService: ConnectionService,
    private val chatService: ChatService,
    private val schedulingService: SchedulingService

) {

    @GetMapping("/{connectionId}")
    fun getConnection(
        @PathVariable connectionId: UUID
    ): ResponseEntity<ConnectionResponse> {
        val connection = connectionService.findByIdOrThrow(
            connectionId = connectionId
        )
        return ResponseEntity.ok(
            ConnectionResponse.from(connection)
        )
    }

    @GetMapping("/{connectionId}/chat")
    fun getSecondChat(
        @PathVariable connectionId: UUID
    ): ResponseEntity<com.reals.backend.controller.dto.ChatResponse> =
        ResponseEntity.ok(
            com.reals.backend.controller.dto.ChatResponse.from(
                chatService.findActiveSecondChatOrThrow(connectionId)
            )
        )

    @GetMapping("/{connectionId}/negotiation")
    fun getNegotiation(
        @PathVariable connectionId: UUID
    ): ResponseEntity<NegotiationResponse> {
        val negotiation = schedulingService.findNegotiationOrThrow(
            connectionId = connectionId
        )
        return ResponseEntity.ok(
            NegotiationResponse.from(negotiation)
        )
    }

    /**
     * Submits a date/time proposal for a user
     * TODO: allow to send more than one proposal?
     * After saving, tryConfirm() runs automatically:
     *  - If overlap found with the other user's proposals -> negotiation CONFIRMED
     *      Connection -> SECOND_CHAT, second ChatSession started
     *  - If no overlap -> stays PENDING, waiting for more proposals
     */
    @PostMapping("/{connectionId}/proposals")
    fun addProposal(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID,
        @RequestBody request: AddProposalRequest
    ): ResponseEntity<ScheduleProposalResponse> {

        val proposal = schedulingService.addProposal(
            connectionId = connectionId,
            userId = userId,
            proposedDateTime = request.proposedDateTime
        )

        // If tryConfirm confirmed the negotiation, start the second chat here
        // SchedulingService does not inject ChatService to avoid a new cycle:
        // SchedulingService -> ChatService -> VisualReviewService -> SchedulingService
        val negotiation = schedulingService.findNegotiationOrNull(connectionId = connectionId)
        val chatId = if (negotiation?.status == NegotiationStatus.CONFIRMED) {
            val connection = connectionService.findByIdOrThrow(connectionId)
            chatService.startSecondChat(connection.matchId, connectionId).id
        } else null
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ScheduleProposalResponse.from(proposal, chatId)
        )
    }

    @GetMapping("/{connectionId}/proposals")
    fun getProposals(
        @PathVariable connectionId: UUID
    ): ResponseEntity<List<ScheduleProposalResponse>> {

        val proposals = schedulingService.getProposals(
            connectionId = connectionId
        )

        return ResponseEntity.ok(
            proposals.map { ScheduleProposalResponse.from(it) }
        )
    }

    /**
     * Accepts the partner's proposal, confirming the negotiation
     *
     * Rules enforced by SchedulingService
     *  - [userId] must NOT be the proposer of [proposalId]
     *  - [userId] must have already submitted their own proposal this round
     *  - Once confirmed -> Connection transitions to SECOND_CHAT, second chat starts
     */
    @PostMapping("/{connectionId}/proposals/{proposalId}/acceptance")
    fun acceptProposal(
        @CurrentUserId userId: UUID,
        @PathVariable proposalId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<NegotiationResponse> {

        val negotiation = schedulingService.acceptProposal(
            proposalId = proposalId,
            acceptorUserId = userId
        )
        // TODO: ver si hay problema en usar en un lado connectionId y en otro el de negotiation
        val connection = connectionService.findByIdOrThrow(negotiation.connectionId)
        val chat = chatService.startSecondChat(connection.matchId, connectionId)
        return ResponseEntity.ok(
            NegotiationResponse.from(negotiation, chat.id)
        )
    }

    /**
     * Opens a new negotiation round when neither user accepted the other's proposal
     * Clears pending proposals and increments round number
     * if maxRounds is exceeded -> negotiation FAILED, Connection CLOSED
     */
    @PostMapping("/{connectionId}/negotiation/rounds")
    fun openNewRound(
        @PathVariable connectionId: UUID
    ): ResponseEntity<NegotiationResponse> {

        val negotiation = schedulingService.openNewRound(
            connectionId = connectionId
        )
        return ResponseEntity.ok(
            NegotiationResponse.from(negotiation)
        )
    }
}
