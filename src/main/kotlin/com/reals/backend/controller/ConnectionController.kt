package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.AddProposalRequest
import com.reals.backend.controller.dto.ChatResponse
import com.reals.backend.controller.dto.ConnectionDismissalResponse
import com.reals.backend.controller.dto.ConnectionResponse
import com.reals.backend.controller.dto.NegotiationResponse
import com.reals.backend.controller.dto.RejectPartnerProposalsRequest
import com.reals.backend.controller.dto.ScheduleProposalResponse
import com.reals.backend.controller.dto.SecondChatAttendanceResponse
import com.reals.backend.controller.dto.SecondChatCompletionDecisionRequest
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.LegalComplianceService
import com.reals.backend.service.SecondChatConversationLifecycleService
import com.reals.backend.service.SecondChatLifecycleService
import com.reals.backend.service.SchedulingService
import com.reals.backend.service.exception.DomainConflictException
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
    private val secondChatLifecycleService: SecondChatLifecycleService,
    private val secondChatConversationLifecycleService: SecondChatConversationLifecycleService,
    private val schedulingService: SchedulingService,
    private val legalComplianceService: LegalComplianceService

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
                c = chatService.findVisibleSecondChatOrThrow(
                    connectionId = connectionId,
                    userId = userId
                ),
                inactivityExpiresAt = null
            )
        )

    @PostMapping("/{connectionId}/second-chat-dismissal")
    fun dismissSecondChatFromHome(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<ConnectionDismissalResponse> {
        connectionService.dismissSecondChatFromHome(
            connectionId = connectionId,
            userId = userId
        )

        return ResponseEntity.ok(ConnectionDismissalResponse(dismissed = true))
    }

    @PostMapping("/{connectionId}/second-chat/join")
    fun joinSecondChat(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<SecondChatAttendanceResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        return when (
            val result = secondChatLifecycleService.joinSecondChat(
                connectionId = connectionId,
                userId = userId
            )
        ) {
            is SecondChatLifecycleService.SecondChatJoinResult.Joined ->
                ResponseEntity.ok(SecondChatAttendanceResponse.from(result.view))

            is SecondChatLifecycleService.SecondChatJoinResult.Rejected ->
                throw DomainConflictException(code = result.code, message = result.message)
        }
    }

    @GetMapping("/{connectionId}/second-chat/status")
    fun getSecondChatStatus(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<SecondChatAttendanceResponse> =
        ResponseEntity.ok(
            SecondChatAttendanceResponse.from(
                secondChatLifecycleService.getSecondChatStatus(
                    connectionId = connectionId,
                    userId = userId
                )
            )
        )

    @PostMapping("/{connectionId}/second-chat/no-show-claims")
    fun createSecondChatNoShowClaim(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<SecondChatAttendanceResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        val result =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = connectionId,
                requesterUserId = userId
            )
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(SecondChatAttendanceResponse.from(result.view))
    }

    @PostMapping("/{connectionId}/second-chat/completion-requests")
    fun createSecondChatCompletionRequest(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<SecondChatAttendanceResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        val result =
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = connectionId,
                requesterUserId = userId
            )
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(SecondChatAttendanceResponse.from(secondChatLifecycleService.getSecondChatStatus(connectionId, userId)))
    }

    @PostMapping("/{connectionId}/second-chat/completion-requests/{requestId}/decision")
    fun decideSecondChatCompletionRequest(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID,
        @PathVariable requestId: UUID,
        @Valid
        @RequestBody request: SecondChatCompletionDecisionRequest
    ): ResponseEntity<SecondChatAttendanceResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        return when (
            val result = secondChatConversationLifecycleService.decideMutualCompletion(
                connectionId = connectionId,
                requestId = requestId,
                responderUserId = userId,
                decision = request.decision
            )
        ) {
            is SecondChatConversationLifecycleService.CompletionDecisionResult.Applied ->
                ResponseEntity.ok(SecondChatAttendanceResponse.from(secondChatLifecycleService.getSecondChatStatus(connectionId, userId)))

            is SecondChatConversationLifecycleService.CompletionDecisionResult.Rejected ->
                throw DomainConflictException(code = result.code, message = result.message)
        }
    }

    @PostMapping("/{connectionId}/second-chat/inactivity-claims")
    fun createSecondChatInactivityClaim(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<SecondChatAttendanceResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        val result =
            secondChatConversationLifecycleService.createPartnerInactivityClaim(
                connectionId = connectionId,
                requesterUserId = userId
            )
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(SecondChatAttendanceResponse.from(secondChatLifecycleService.getSecondChatStatus(connectionId, userId)))
    }

    @GetMapping("/{connectionId}/negotiation")
    fun getNegotiation(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<NegotiationResponse> {
        val connection = connectionService.findByIdForUserOrThrow(
            connectionId = connectionId,
            userId = userId
        )

        val negotiation = schedulingService.findNegotiationOrThrow(
            connectionId = connectionId
        )
        return ResponseEntity.ok(
            NegotiationResponse.from(
                n = negotiation,
                schedulingExpiresAt = connection.schedulingExpiresAt
            )
        )
    }

    /**
     * Submits the user's ordered second-chat slot proposals for the expected current round.
     * After saving, tryConfirm() runs automatically:
     *  - If overlap is found with the other user's proposals -> negotiation CONFIRMED
     *      Connection -> SECOND_CHAT_SCHEDULED. The second chat starts at confirmedDateTime.
     *  - If no overlap -> stays PENDING so each user can accept a partner slot or reject partner proposals.
     */
    @PostMapping("/{connectionId}/proposals")
    fun addProposal(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID,
        @Valid
        @RequestBody request: AddProposalRequest
    ): ResponseEntity<List<ScheduleProposalResponse>> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        val proposals = schedulingService.addProposals(
            connectionId = connectionId,
            userId = userId,
            expectedRoundNumber = request.expectedRoundNumber,
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
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        val negotiation = schedulingService.acceptProposal(
            connectionId = connectionId,
            proposalId = proposalId,
            acceptorUserId = userId
        )
        val connection = connectionService.findByIdForUserOrThrow(
            connectionId = connectionId,
            userId = userId
        )
        return ResponseEntity.ok(
            NegotiationResponse.from(
                n = negotiation,
                schedulingExpiresAt = connection.schedulingExpiresAt
            )
        )
    }

    /**
     * Explicitly rejects pending partner proposals in the expected current round.
     * The round advances only after both users' proposal lists are resolved.
     */
    @PostMapping("/{connectionId}/negotiation/rejections")
    fun rejectPartnerProposals(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID,
        @Valid
        @RequestBody request: RejectPartnerProposalsRequest
    ): ResponseEntity<NegotiationResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        val negotiation = schedulingService.rejectPartnerProposals(
            connectionId = connectionId,
            userId = userId,
            expectedRoundNumber = request.expectedRoundNumber
        )
        val connection = connectionService.findByIdForUserOrThrow(
            connectionId = connectionId,
            userId = userId
        )
        return ResponseEntity.ok(
            NegotiationResponse.from(
                n = negotiation,
                schedulingExpiresAt = connection.schedulingExpiresAt
            )
        )
    }
}
