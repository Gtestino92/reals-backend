package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingProperties
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.UserBlockRepository
import com.reals.backend.repository.VisualReviewRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class MatchmakingPairEligibilityService(
    private val properties: MatchmakingProperties,
    private val matchRepository: MatchRepository,
    private val connectionRepository: ConnectionRepository,
    private val chatRepository: ChatRepository,
    private val visualReviewRepository: VisualReviewRepository,
    private val userBlockRepository: UserBlockRepository
) {

    fun previousPairingCutoff(now: OffsetDateTime): OffsetDateTime =
        now.minusDays(properties.previousPairingCooldownDays)

    fun firstChatExpirationCutoff(now: OffsetDateTime): OffsetDateTime =
        now.minusDays(properties.firstChatExpirationCooldownDays)

    fun isHistoricalExclusionEnabled(): Boolean =
        properties.excludePreviousPairing

    fun requirePairCanCreateMatch(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        check(!hasActiveInteraction(userAId, userBId)) {
            "Cannot create match: users already have an active interaction"
        }
        check(!hasActiveHistoricalCooldown(userAId, userBId, now)) {
            "Cannot create match: users are inside previous-pairing cooldown"
        }
    }

    fun isPairEligible(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean =
        !userBlockRepository.existsBetweenUsers(userAId, userBId) &&
            !hasActiveInteraction(userAId, userBId) &&
            !hasActiveHistoricalCooldown(userAId, userBId, now)

    fun hasActiveInteraction(
        userAId: UUID,
        userBId: UUID
    ): Boolean {
        val activeMatchExists =
            matchRepository
                .findBetweenUsersAndStateIn(
                    userAId = userAId,
                    userBId = userBId,
                    states = listOf(MatchState.CHAT_ACTIVE, MatchState.VISUAL_PHASE)
                )
                .isNotEmpty()

        if (activeMatchExists) {
            return true
        }

        val approvedWithoutConnectionExists =
            matchRepository
                .findBetweenUsersAndStateIn(
                    userAId = userAId,
                    userBId = userBId,
                    states = listOf(MatchState.VISUAL_APPROVED)
                )
                .any { match -> connectionRepository.findByMatchId(match.id) == null }

        if (approvedWithoutConnectionExists) {
            return true
        }

        return connectionRepository
            .findBetweenUsersAndStateIn(
                userAId = userAId,
                userBId = userBId,
                states = ConnectionState.entries.filter { it != ConnectionState.CLOSED }
            )
            .isNotEmpty()
    }

    fun hasActiveHistoricalCooldown(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean {
        if (!properties.excludePreviousPairing) {
            return false
        }

        val previousPairingCutoff = previousPairingCutoff(now)
        val firstChatExpirationCutoff = firstChatExpirationCutoff(now)

        val matches =
            matchRepository.findBetweenUsersAndStateIn(
                userAId = userAId,
                userBId = userBId,
                states = listOf(
                    MatchState.CHAT_REJECTED,
                    MatchState.VISUAL_REJECTED,
                    MatchState.EXPIRED
                )
            )

        if (
            matches.any {
                isRecentThirtyDayMatchOutcome(
                    match = it,
                    cutoff = previousPairingCutoff
                ) ||
                    isRecentFirstChatExpiration(
                        match = it,
                        cutoff = firstChatExpirationCutoff
                    )
            }
        ) {
            return true
        }

        return connectionRepository
            .findBetweenUsersAndStateIn(
                userAId = userAId,
                userBId = userBId,
                states = listOf(ConnectionState.CLOSED)
            )
            .any { it.updatedAt.isAfter(previousPairingCutoff) }
    }

    private fun isRecentThirtyDayMatchOutcome(
        match: Match,
        cutoff: OffsetDateTime
    ): Boolean =
        when (match.state) {
            MatchState.CHAT_REJECTED,
            MatchState.VISUAL_REJECTED -> match.updatedAt.isAfter(cutoff)

            MatchState.EXPIRED -> visualReviewRepository.findByMatchId(match.id) != null &&
                match.updatedAt.isAfter(cutoff)

            MatchState.CHAT_ACTIVE,
            MatchState.VISUAL_PHASE,
            MatchState.VISUAL_APPROVED -> false
        }

    private fun isRecentFirstChatExpiration(
        match: Match,
        cutoff: OffsetDateTime
    ): Boolean {
        if (match.state != MatchState.EXPIRED) {
            return false
        }
        if (visualReviewRepository.findByMatchId(match.id) != null) {
            return false
        }

        val firstChat =
            chatRepository.findByMatchIdAndChatType(
                matchId = match.id,
                chatType = ChatType.FIRST_CHAT
            )

        val terminalAt =
            firstChat
                ?.takeIf(::isAutomaticFirstChatTerminal)
                ?.endedAt
                ?: match.updatedAt

        return terminalAt.isAfter(cutoff)
    }

    private fun isAutomaticFirstChatTerminal(chat: Chat): Boolean =
        (chat.status == ChatStatus.EXPIRED && chat.endedReason == ChatEndReason.ABSOLUTE_TIMEOUT) ||
            (chat.status == ChatStatus.ABANDONED && chat.endedReason == ChatEndReason.INACTIVITY_TIMEOUT)
}
