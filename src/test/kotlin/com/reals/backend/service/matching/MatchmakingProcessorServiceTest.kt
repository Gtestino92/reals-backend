package com.reals.backend.service.matching

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Match
import com.reals.backend.service.ChatService
import com.reals.backend.service.MatchService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.time.OffsetDateTime
import java.util.UUID

class MatchmakingProcessorServiceTest {

    @Test
    fun `processor reports limit not exhausted when no candidate exists before max attempts`() {
        val fixture = processorFixture()
        Mockito.`when`(fixture.matchmakingService.claimNextCandidatePair())
            .thenReturn(null)

        val result = fixture.processor.process(maxPairsPerRun = 10)

        assertEquals(0, result.candidatePairs)
        assertEquals(0, result.matchesCreated)
        assertEquals(0, result.failedPairs)
        assertFalse(result.limitExhausted)
    }

    @Test
    fun `processor reports limit exhausted when all allowed attempts create matches`() {
        val fixture = processorFixture()
        fixture.enqueueCandidatePairs(10)
        Mockito.`when`(fixture.matchService.createMatch(anyUuid(), anyUuid()))
            .thenAnswer { invocation ->
                Match(
                    userAId = invocation.arguments[0] as UUID,
                    userBId = invocation.arguments[1] as UUID
                )
            }
        Mockito.`when`(fixture.chatService.startFirstChat(anyUuid()))
            .thenAnswer { invocation ->
                Chat(
                    matchId = invocation.arguments[0] as UUID,
                    chatType = ChatType.FIRST_CHAT,
                    timeoutAt = NOW.plusMinutes(5)
                )
            }

        val result = fixture.processor.process(maxPairsPerRun = 10)

        assertEquals(10, result.candidatePairs)
        assertEquals(10, result.matchesCreated)
        assertEquals(0, result.failedPairs)
        assertTrue(result.limitExhausted)
    }

    @Test
    fun `processor reports limit exhausted when capacity removals consume every allowed attempt`() {
        val fixture = processorFixture()
        fixture.enqueueCandidatePairs(10)
        Mockito.`when`(fixture.matchService.createMatch(anyUuid(), anyUuid()))
            .thenThrow(
                DomainConflictException(
                    code = DomainErrorCode.ACTIVE_MATCH_LIMIT_REACHED,
                    message = "active match cap reached"
                )
            )

        val result = fixture.processor.process(maxPairsPerRun = 10)

        assertEquals(10, result.candidatePairs)
        assertEquals(0, result.matchesCreated)
        assertEquals(0, result.failedPairs)
        assertTrue(result.limitExhausted)
        Mockito.verify(fixture.matchmakingService, Mockito.times(10))
            .removeAdmissionCappedQueueEntries(anyUuid(), anyUuid())
    }

    @Test
    fun `processor reports limit not exhausted when a failed pair stops processing early`() {
        val fixture = processorFixture()
        fixture.enqueueCandidatePairs(10)
        Mockito.`when`(fixture.matchService.createMatch(anyUuid(), anyUuid()))
            .thenThrow(RuntimeException("match creation failed"))

        val result = fixture.processor.process(maxPairsPerRun = 10)

        assertEquals(1, result.candidatePairs)
        assertEquals(0, result.matchesCreated)
        assertEquals(1, result.failedPairs)
        assertFalse(result.limitExhausted)
    }

    private fun processorFixture(): ProcessorFixture {
        val matchmakingService = Mockito.mock(MatchmakingService::class.java)
        val matchService = Mockito.mock(MatchService::class.java)
        val chatService = Mockito.mock(ChatService::class.java)
        return ProcessorFixture(
            matchmakingService = matchmakingService,
            matchService = matchService,
            chatService = chatService,
            processor = MatchmakingProcessorService(
                matchmakingService = matchmakingService,
                matchService = matchService,
                chatService = chatService,
                eventPublisher = Mockito.mock(ApplicationEventPublisher::class.java),
                transactionManager = NoOpTransactionManager()
            )
        )
    }

    private data class ProcessorFixture(
        val matchmakingService: MatchmakingService,
        val matchService: MatchService,
        val chatService: ChatService,
        val processor: MatchmakingProcessorService
    ) {
        fun enqueueCandidatePairs(count: Int) {
            val pairs =
                ArrayDeque(
                    (0..<count).map {
                        UUID.randomUUID() to UUID.randomUUID()
                    }
                )
            Mockito.`when`(matchmakingService.claimNextCandidatePair())
                .thenAnswer {
                    if (pairs.isEmpty()) null else pairs.removeFirst()
                }
        }
    }

    private fun anyUuid(): UUID {
        Mockito.any(UUID::class.java)
        return UUID.randomUUID()
    }

    private class NoOpTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit

        override fun doCommit(status: DefaultTransactionStatus) = Unit

        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-07-17T12:00:00Z")
    }
}
