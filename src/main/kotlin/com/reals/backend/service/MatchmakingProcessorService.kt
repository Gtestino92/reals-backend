package com.reals.backend.service

import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchmakingPairProcessingException
import com.reals.backend.domain.MatchmakingProcessResult
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

    fun processBatch(batchSize: Int): MatchmakingProcessResult {
        require(batchSize > 0) {
            "Batch size must be greater than 0"
        }

        val createdMatches = mutableListOf<Match>()
        var candidatePairs = 0
        var failedPairs = 0

        // Claim and process one pair per transaction. This keeps queue row
        // locks held from candidate selection through match/chat creation,
        // while allowing previous successful pairs to stay committed if a
        // later pair fails.
        for (attempt in 0 until batchSize) {
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
                matchmakingService.findCandidatePairs(
                    batchSize = 1
                ).firstOrNull()
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
