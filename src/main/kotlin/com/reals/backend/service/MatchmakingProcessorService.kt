package com.reals.backend.service

import com.reals.backend.domain.Match
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

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

        for (attempt in 0 until batchSize) {
            try {
                when (val result = processNextCandidatePair()) {
                    MatchmakingSingleResult.NoCandidatePair -> return MatchmakingProcessResult(
                        candidatePairs = candidatePairs,
                        matchesCreated = createdMatches.size,
                        failedPairs = failedPairs,
                        matches = createdMatches
                    )

                    is MatchmakingSingleResult.Created -> {
                        candidatePairs += 1
                        createdMatches.add(result.match)
                    }
                }
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

    private fun processNextCandidatePair(): MatchmakingSingleResult =
        transactionTemplate.execute {
            val (userAId, userBId) =
                matchmakingService.findCandidatePairs(
                    batchSize = 1
                ).firstOrNull()
                    ?: return@execute MatchmakingSingleResult.NoCandidatePair

            try {
                val match = matchService.createMatch(
                    userAId = userAId,
                    userBId = userBId
                )

                chatService.startFirstChat(
                    matchId = match.id
                )

                MatchmakingSingleResult.Created(match)
            } catch (ex: RuntimeException) {
                throw MatchmakingPairProcessingException(
                    userAId = userAId,
                    userBId = userBId,
                    cause = ex
                )
            }
        } ?: MatchmakingSingleResult.NoCandidatePair
}

data class MatchmakingProcessResult(
    val candidatePairs: Int,
    val matchesCreated: Int,
    val failedPairs: Int,
    val matches: List<Match>
)

private sealed class MatchmakingSingleResult {
    data object NoCandidatePair : MatchmakingSingleResult()

    data class Created(
        val match: Match
    ) : MatchmakingSingleResult()
}

private class MatchmakingPairProcessingException(
    val userAId: UUID,
    val userBId: UUID,
    cause: RuntimeException
) : RuntimeException(cause)
