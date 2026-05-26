package com.reals.backend.controller

import com.reals.backend.config.CurrentUserId
import com.reals.backend.controller.dto.*
import com.reals.backend.domain.MatchState
import com.reals.backend.service.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/matches")
class MatchController(
    private val matchService: MatchService,
    private val chatService: ChatService,
    private val visualReviewService: VisualReviewService,
    private val connectionService: ConnectionService,
    private val profileService: ProfileService
) {

    @GetMapping("/{matchId}")
    fun getMatch(
        @PathVariable matchId: UUID
    ): ResponseEntity<MatchResponse> {
        val match = matchService.findByIdOrThrow(matchId = matchId)
        val connectionId = connectionService.findConnectionIdByMatchId(matchId = matchId)
        return ResponseEntity.ok(MatchResponse.from(match = match, connectionId = connectionId))
    }

    @GetMapping("/{matchId}/chat")
    fun getFirstChat(
        @PathVariable matchId: UUID
    ): ResponseEntity<ChatResponse> =
        ResponseEntity.ok(
            ChatResponse.from(
                chatService.findActiveFirstChatOrThrow(matchId)
            )
        )

    /*
        Returns the profile of the OTHER user in the match, ass seen from [requestingUserId]
        Only available when the match is in VISUAL_PHASE or later
        Includes photos so the requesting user can make the visual decision
     */
    @GetMapping("/{matchId}/visual-profile")
    fun getVisualPhaseProfile(
        @CurrentUserId userId: UUID,
        @PathVariable matchId: UUID
    ): ResponseEntity<VisualProfileResponse> {

        val match = matchService.findByIdOrThrow(
            matchId = matchId
        )

        val partnerId =
            when (userId) {
                match.userAId -> match.userBId
                match.userBId -> match.userAId
                else ->
                    throw IllegalArgumentException(
                        "User $userId does not belong to match $matchId"
                    )
            }

        val partnerProfile = profileService.findByUserId(partnerId)
            ?: throw NoSuchElementException(
                "Profile not found for partner: $partnerId"
            )

        val photos = profileService.getPhotos(
            profileId = partnerProfile.id
        )

        return ResponseEntity.ok(
            VisualProfileResponse.from(
                profile = partnerProfile,
                photos = photos
            )
        )
    }

    /*
        Records an individual chat continuation decision for a user (APPROVED/REJECTED)
        When both users have decided:
            - BOTH APPROVED -> Match transitions CHAT_ACTIVE -> VISUAL_PHASE, VisualReview created
            - ANY REJECTED -> Match transitions to CHAT_REJECTED, locks released
     */
    @PostMapping("/{matchId}/chat-decision")
    fun recordChatDecision(
        @CurrentUserId userId: UUID,
        @PathVariable matchId: UUID,
        @RequestBody request: ChatDecisionRequest
    ): ResponseEntity<MatchResponse> {
        chatService.recordChatDecision(
            matchId = matchId,
            userId = userId,
            decision = request.decision
        )
        val match = matchService.findByIdOrThrow(matchId)
        val connectionId =
            connectionService.findConnectionIdByMatchId(
                matchId = match.id
            )

        return ResponseEntity.ok(
            MatchResponse.from(
                match = match,
                connectionId = connectionId
            )
        )
    }

    /**
     * Records a visual decision for a user (APPROVED or REJECTED)
     * When both users have decided, the match transitions automatically
     *  - mutual APPROVED -> VISUAL_APPROVED, Connection created
     *  - any REJECTED -> VISUAL_REJECTED, locks released
     */
    @PostMapping("/{matchId}/visual-decision")
    fun recordVisualDecision(
        @CurrentUserId userId: UUID,
        @PathVariable matchId: UUID,
        @RequestBody request: VisualDecisionRequest
    ): ResponseEntity<MatchResponse> {
        visualReviewService.recordDecision(
            matchId = matchId,
            userId = userId,
            decision = request.decision
        )
        val match = matchService.findByIdOrThrow(
            matchId = matchId
        )
        val connectionId =
            connectionService.findConnectionIdByMatchId(
                matchId = matchId
            )
        return ResponseEntity.ok(
            MatchResponse.from(
                match = match,
                connectionId = connectionId
            )
        )
    }

    /**
     * Records the personal message a user writes to the other
     * Only meaningful after mutual visual approval (messagesVisible = true)
     */
    @PutMapping("/{matchId}/personal-message")
    fun recordPersonalMessage(
        @CurrentUserId userId: UUID,
        @PathVariable matchId: UUID,
        @RequestBody request: PersonalMessageRequest
    ): ResponseEntity<Void> {

        visualReviewService.recordPersonalMessage(
            matchId = matchId,
            userId = userId,
            message = request.message
        )

        return ResponseEntity.noContent()
            .build()
    }

    /**
     * Returns the personal message the partner left for the authenticated user
     * message is null if the partner hasn't submitted one yet
     *
     * Reads the requesting userId from the SecurityContext (DevAutoAuthFilter in local,
     * JWT in prod - no query param needed, already aligned with PENDING.md #9)
     * Available from VISUAL_APPROVED onwards
     * TODO (front): llamar al entrar en la pantalla de negociación de horario
     * TODO (product): ver PENDING.md #17 - visibilidad del mensaje del partner
     */
    @GetMapping("/{matchId}/partner-message")
    fun getPartnerMessage(
        @PathVariable matchId: UUID,
        @CurrentUserId requestingUserId: UUID
    ): ResponseEntity<PartnerMessageResponse> {
        val match = matchService.findByIdOrThrow(matchId = matchId)
        check(match.state == MatchState.VISUAL_APPROVED)
        val review = visualReviewService.findByMatchIdOrThrow(matchId = matchId)

        val partnerMessage =
            when (requestingUserId) {
                match.userAId -> review.personalMessageB
                match.userBId -> review.personalMessageA
                else ->
                    error(
                        "User $requestingUserId does not belong to match $matchId"
                    )
            }

        return ResponseEntity.ok(
            PartnerMessageResponse(
                message = partnerMessage
            )
        )
    }
}
