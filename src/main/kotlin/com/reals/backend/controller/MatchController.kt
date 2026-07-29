package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.*
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.VisualDecision
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.*
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime
import java.util.*

@RestController
@RequestMapping("/api/matches")
class MatchController(
    private val matchService: MatchService,
    private val chatService: ChatService,
    private val visualReviewService: VisualReviewService,
    private val connectionService: ConnectionService,
    private val profileService: ProfileService,
    private val userBlockCommandService: UserBlockCommandService,
    private val legalComplianceService: LegalComplianceService,
    private val chatAudioPolicyService: ChatAudioPolicyService
) {

    @PostMapping("/{matchId}/block")
    fun blockMatchParticipant(
        @CurrentUserId userId: UUID,
        @PathVariable matchId: UUID
    ): ResponseEntity<UserBlockResponse> {
        val match = matchService.findByIdForUserOrThrow(matchId, userId)
        val counterpartId = if (match.userAId == userId) match.userBId else match.userAId
        val result = userBlockCommandService.blockUserAndContain(
            blockerUserId = userId,
            blockedUserId = counterpartId,
            source = UserBlockSource.MANUAL
        )
        val response = UserBlockResponse.from(result.block)
        return if (result.created) {
            ResponseEntity.status(201).body(response)
        } else {
            ResponseEntity.ok(response)
        }
    }

    @GetMapping("/{matchId}")
    fun getMatch(
        @CurrentUserId userId: UUID,
        @PathVariable matchId: UUID
    ): ResponseEntity<MatchResponse> {
        val match = matchService.findByIdForUserOrThrow(
            matchId = matchId,
            userId = userId
        )
        val connectionId = connectionService.findConnectionIdByMatchId(matchId = matchId)
        return ResponseEntity.ok(
            MatchResponse.from(
                match = match,
                connectionId = connectionId,
                visualExpiresAt = visualReviewService.visualExpiresAt(match.id)
            )
        )
    }

    @GetMapping("/{matchId}/chat")
    fun getFirstChat(
        @CurrentUserId userId: UUID,
        @PathVariable matchId: UUID
    ): ResponseEntity<FirstChatResponse> {
        val match = matchService.findByIdForUserOrThrow(
            matchId = matchId,
            userId = userId
        )
        val partnerId =
            when (userId) {
                match.userAId -> match.userBId
                match.userBId -> match.userAId
                else -> error("User was already validated as match participant")
            }

        val partnerProfile = profileService.findByUserId(partnerId)
            ?: throw DomainNotFoundException(
                code = DomainErrorCode.PROFILE_NOT_FOUND,
                message = "Partner profile not found"
            )

        val decisions = chatService.getFirstChatDecisionStatuses(
            matchId = matchId,
            userId = userId
        )
        val chat = chatService.findActiveFirstChatForUserOrThrow(
            matchId = matchId,
            userId = userId
        )
        val serverTime = OffsetDateTime.now()

        return ResponseEntity.ok(
            FirstChatResponse.from(
                chat = chat,
                partner = partnerProfile,
                myDecision = decisions.myDecision,
                partnerDecision = decisions.partnerDecision,
                inactivityExpiresAt = chatService.inactivityExpiresAt(chat),
                guidance = chatService.getFirstChatGuidanceState(
                    chat = chat,
                    userId = userId
                )?.let { FirstChatGuidanceResponse.from(it) },
                serverTime = serverTime,
                audioPolicy = ChatAudioPolicyResponse.from(
                    chatAudioPolicyService.policyFor(chat = chat, userId = userId)
                )
            )
        )
    }

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

        val access = visualReviewService.requireVisualContentAccess(
            matchId = matchId,
            userId = userId
        )
        val match = access.match

        val partnerId =
            when (userId) {
                match.userAId -> match.userBId
                match.userBId -> match.userAId
                else -> error("User was already validated as match participant")
            }

        val partnerProfile = profileService.findByUserId(partnerId)
            ?: throw DomainNotFoundException(
                code = DomainErrorCode.PROFILE_NOT_FOUND,
                message = "Partner profile not found"
            )

        val photos = profileService.getPhotoResponses(
            profileId = partnerProfile.id
        )
        val personalMessageStatus = visualReviewService.getPersonalMessageStatusForUser(
            matchId = matchId,
            userId = userId
        )
        val visualExpiresAt = visualReviewService.visualExpiresAt(matchId)

        return ResponseEntity.ok(
            VisualProfileResponse.from(
                profile = partnerProfile,
                photos = photos,
                myPersonalMessageSubmitted =
                    visualReviewService.hasPersonalMessageSubmitted(
                        matchId = matchId,
                        userId = userId
                    ),
                partnerPersonalMessageSubmitted =
                    personalMessageStatus.partnerPersonalMessageSubmitted,
                partnerPersonalMessageRead =
                    personalMessageStatus.partnerPersonalMessageRead,
                decisionRequiresPartnerPersonalMessageRead =
                    personalMessageStatus.decisionRequiresPartnerPersonalMessageRead,
                visualExpiresAt = visualExpiresAt
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
        @Valid
        @RequestBody request: ChatDecisionRequest
    ): ResponseEntity<MatchResponse> {
        if (request.decision == ChatContinueDecision.APPROVED) {
            legalComplianceService.requireCurrentRequirementsSatisfied(userId)
        }

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
                connectionId = connectionId,
                visualExpiresAt = visualReviewService.visualExpiresAt(match.id)
            )
        )
    }

    /**
     * Records a visual decision for a user (APPROVED or REJECTED)
     * The user's match lock is released immediately.
     * When both users have decided, the match transitions automatically:
     *  - mutual APPROVED -> VISUAL_APPROVED, pending Connection created
     *  - any REJECTED -> VISUAL_REJECTED, remaining locks released
     */
    @PostMapping("/{matchId}/visual-decision")
    fun recordVisualDecision(
        @CurrentUserId userId: UUID,
        @PathVariable matchId: UUID,
        @Valid
        @RequestBody request: VisualDecisionRequest
    ): ResponseEntity<MatchResponse> {
        if (request.decision == VisualDecision.APPROVED) {
            legalComplianceService.requireCurrentRequirementsSatisfied(userId)
        }

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
                connectionId = connectionId,
                visualExpiresAt = visualReviewService.visualExpiresAt(match.id)
            )
        )
    }

    /**
     * Records the optional personal message a user writes to the other during visual review.
     */
    @PutMapping("/{matchId}/personal-messages/me")
    fun recordPersonalMessage(
        @CurrentUserId userId: UUID,
        @PathVariable matchId: UUID,
        @Valid
        @RequestBody request: PersonalMessageRequest
    ): ResponseEntity<Void> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

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
     * Firebase/JWT in dev/prod - no query param needed)
     * Available from VISUAL_PHASE onwards
     */
    @GetMapping("/{matchId}/personal-messages/partner")
    fun getPartnerMessage(
        @PathVariable matchId: UUID,
        @CurrentUserId requestingUserId: UUID
    ): ResponseEntity<PartnerMessageResponse> {
        return ResponseEntity.ok(
            PartnerMessageResponse(
                message = visualReviewService.getPartnerMessage(
                    matchId = matchId,
                    requestingUserId = requestingUserId
                )
            )
        )
    }
}
