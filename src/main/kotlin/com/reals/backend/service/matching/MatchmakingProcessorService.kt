package com.reals.backend.service.matching

import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchmakingPairProcessingException
import com.reals.backend.domain.MatchmakingProcessResult
import com.reals.backend.service.ChatService
import com.reals.backend.service.MatchFoundEvent
import com.reals.backend.service.MatchService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class MatchmakingProcessorService(
    private val matchmakingService: MatchmakingService,
    private val matchService: MatchService,
    private val chatService: ChatService,
    private val eventPublisher: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    fun process(maxPairsPerRun: Int): MatchmakingProcessResult {
        require(maxPairsPerRun > 0) {
            "Max pairs per run must be greater than 0"
        }

        val createdMatches = mutableListOf<Match>()
        var candidatePairs = 0
        var failedPairs = 0

        // Claim and process one pair per transaction. This keeps queue row
        // locks held from candidate selection through match/chat creation,
        // while allowing previous successful pairs to stay committed if a
        // later pair fails.
        for (attempt in 0..<maxPairsPerRun) {
            try {
                val match = claimAndProcessNextCandidatePair()
                    ?: return MatchmakingProcessResult(
                        candidatePairs = candidatePairs,
                        matchesCreated = createdMatches.size,
                        failedPairs = failedPairs,
                        matches = createdMatches,
                        limitExhausted = false
                    )

                candidatePairs += 1
                createdMatches.add(match)
            } catch (ex: MatchmakingPairProcessingException) {
                if (ex.isVisualAdvancementLimit()) {
                    candidatePairs += 1
                    matchmakingService.removeAdmissionCappedQueueEntries(
                        userAId = ex.userAId,
                        userBId = ex.userBId
                    )
                    continue
                }

                if (ex.isActiveEngagementCapacityLimit()) {
                    candidatePairs += 1
                    matchmakingService.removeAdmissionCappedQueueEntries(
                        userAId = ex.userAId,
                        userBId = ex.userBId
                    )
                    continue
                }

                candidatePairs += 1
                failedPairs += 1
                log.error(
                    "MatchmakingProcessorService - failed to create match for userA={} userB={}",
                    ex.userAId,
                    ex.userBId,
                    ex.cause
                )
                return MatchmakingProcessResult(
                    candidatePairs = candidatePairs,
                    matchesCreated = createdMatches.size,
                    failedPairs = failedPairs,
                    matches = createdMatches,
                    limitExhausted = false
                )
            }
        }

        return MatchmakingProcessResult(
            candidatePairs = candidatePairs,
            matchesCreated = createdMatches.size,
            failedPairs = failedPairs,
            matches = createdMatches,
            limitExhausted = true
        )
    }

    private fun claimAndProcessNextCandidatePair(): Match? =
        transactionTemplate.execute<Match?> {
            val (userAId, userBId) =
                matchmakingService.claimNextCandidatePair()
                    ?: return@execute null

            try {
                val match = matchService.createMatch(
                    userAId = userAId,
                    userBId = userBId
                )

                val chat =
                    chatService.startFirstChat(
                        matchId = match.id
                    )

                eventPublisher.publishEvent(
                    MatchFoundEvent(
                        matchId = match.id,
                        chatId = chat.id
                    )
                )

                match
            } catch (ex: RuntimeException) {
                throw MatchmakingPairProcessingException(
                    userAId = userAId,
                    userBId = userBId,
                    cause = ex
                )
            }
        }

    private fun MatchmakingPairProcessingException.isVisualAdvancementLimit(): Boolean {
        val conflict = cause as? DomainConflictException ?: return false
        return conflict.code == DomainErrorCode.VISUAL_ADVANCEMENT_LIMIT_REACHED
    }

    private fun MatchmakingPairProcessingException.isActiveEngagementCapacityLimit(): Boolean {
        val conflict = cause as? DomainConflictException ?: return false
        return conflict.code == DomainErrorCode.ACTIVE_MATCH_LIMIT_REACHED ||
            conflict.code == DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED
    }
}
