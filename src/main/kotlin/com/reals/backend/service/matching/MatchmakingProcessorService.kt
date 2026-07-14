package com.reals.backend.service.matching

import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchmakingPairProcessingException
import com.reals.backend.domain.MatchmakingProcessResult
import com.reals.backend.service.ChatService
import com.reals.backend.service.MatchService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class MatchmakingProcessorService(
    private val matchmakingService: MatchmakingService,
    private val matchService: MatchService,
    private val chatService: ChatService,
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
                        matches = createdMatches
                    )

                candidatePairs += 1
                createdMatches.add(match)
            } catch (ex: MatchmakingPairProcessingException) {
                candidatePairs += 1
                failedPairs += 1
                log.error(
                    "MatchmakingProcessorService - failed to create match for userA={} userB={}",
                    ex.userAId,
                    ex.userBId,
                    ex.cause
                )
                break
            }
        }

        return MatchmakingProcessResult(
            candidatePairs = candidatePairs,
            matchesCreated = createdMatches.size,
            failedPairs = failedPairs,
            matches = createdMatches
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

                chatService.startFirstChat(
                    matchId = match.id
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
}
