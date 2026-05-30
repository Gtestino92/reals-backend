package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.AddProposalRequest
import com.reals.backend.controller.dto.ChatResponse
import com.reals.backend.controller.dto.ConnectionResponse
import com.reals.backend.controller.dto.NegotiationResponse
import com.reals.backend.controller.dto.ScheduleProposalResponse
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.SchedulingService
import jakarta.validation.Valid
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
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<ConnectionResponse> {
        val connection = connectionService.findByIdForUserOrThrow(
            connectionId = connectionId,
            userId = userId
        )
        return ResponseEntity.ok(
            ConnectionResponse.from(connection)
        )
    }

    @GetMapping("/{connectionId}/chat")
    fun getSecondChat(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<ChatResponse> =
        ResponseEntity.ok(
            ChatResponse.from(
                chatService.findVisibleSecondChatOrThrow(
                    connectionId = connectionId,
                    userId = userId
                )
            )
        )

    @GetMapping("/{connectionId}/negotiation")
    fun getNegotiation(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<NegotiationResponse> {
        connectionService.findByIdForUserOrThrow(
            connectionId = connectionId,
            userId = userId
        )

        val negotiation = schedulingService.findNegotiationOrThrow(
            connectionId = connectionId
        )
        return ResponseEntity.ok(
            NegotiationResponse.from(negotiation)
        )
    }

    /**
     * Submits the user's ordered second-chat slot proposals for the current round.
     * After saving, tryConfirm() runs automatically:
     *  - If overlap is found with the other user's proposals -> negotiation CONFIRMED
     *      Connection -> SECOND_CHAT_SCHEDULED. The second chat starts at confirmedDateTime.
     *  - If no overlap -> stays PENDING so each user can accept a partner slot or reject the round.
     */
    @PostMapping("/{connectionId}/proposals")
    fun addProposal(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID,
        @Valid
        @RequestBody request: AddProposalRequest
    ): ResponseEntity<List<ScheduleProposalResponse>> {

        val proposals = schedulingService.addProposals(
            connectionId = connectionId,
            userId = userId,
            proposedDateTimes = request.proposedDateTimes
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(
            proposals.map { ScheduleProposalResponse.from(it) }
        )
    }

    @GetMapping("/{connectionId}/proposals")
    fun getProposals(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<List<ScheduleProposalResponse>> {
        connectionService.findByIdForUserOrThrow(
            connectionId = connectionId,
            userId = userId
        )

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
     *  - Once confirmed -> Connection transitions to SECOND_CHAT_SCHEDULED. The second chat
     *    starts at confirmedDateTime.
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
        return ResponseEntity.ok(
            NegotiationResponse.from(negotiation)
        )
    }

    /**
     * Explicitly rejects the current round and automatically opens the next one.
     * If maxRounds is exceeded -> negotiation FAILED, Connection CLOSED.
     */
    @PostMapping("/{connectionId}/negotiation/rejections")
    fun rejectCurrentRound(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<NegotiationResponse> {

        val negotiation = schedulingService.rejectCurrentRound(
            connectionId = connectionId,
            userId = userId
        )
        return ResponseEntity.ok(
            NegotiationResponse.from(negotiation)
        )
    }
}
