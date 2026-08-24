package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ActiveEngagementLock
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.VisualDecision
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.MatchService
import com.reals.backend.service.UserService
import com.reals.backend.service.VisualReviewService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class EngagementConcurrencyIntegrationTest {

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var matchService: MatchService

    @Autowired
    private lateinit var connectionService: ConnectionService

    @Autowired
    private lateinit var chatService: ChatService

    @Autowired
    private lateinit var visualReviewService: VisualReviewService

    @Autowired
    private lateinit var lockRepository: ActiveEngagementLockRepository

    @Autowired
    private lateinit var connectionRepository: ConnectionRepository

    @Test
    fun `concurrent match creation cannot exceed active match limit`() {
        val sharedUserId = createUser("shared-match")

        repeat(4) {
            matchService.createMatch(
                userAId = sharedUserId,
                userBId = createUser("existing-match-$it")
            )
        }

        val results = runConcurrently(
            {
                matchService.createMatch(
                    userAId = sharedUserId,
                    userBId = createUser("concurrent-match-a")
                )
            },
            {
                matchService.createMatch(
                    userAId = sharedUserId,
                    userBId = createUser("concurrent-match-b")
                )
            }
        )

        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
        assertEquals(
            5,
            lockRepository.countByUserIdAndEngagementType(sharedUserId, EngagementType.MATCH)
        )
    }

    @Test
    fun `concurrent connection creation may exceed active connection limit`() {
        val sharedUserId = createUser("shared-connection")

        val candidateMatchA = matchService.createMatch(
            userAId = sharedUserId,
            userBId = createUser("concurrent-connection-a")
        )
        val candidateMatchB = matchService.createMatch(
            userAId = sharedUserId,
            userBId = createUser("concurrent-connection-b")
        )
        saveConnectionLocks(sharedUserId, count = 4)

        val results = runConcurrently(
            { connectionService.createFromMatch(candidateMatchA) },
            { connectionService.createFromMatch(candidateMatchB) }
        )

        assertEquals(2, results.count { it })
        assertEquals(0, results.count { !it })
        assertEquals(
            6,
            lockRepository.countByUserIdAndEngagementType(sharedUserId, EngagementType.CONNECTION)
        )
    }

    @Test
    fun `mutual visual approval creates connection when participant is at active connection limit`() {
        val sharedUserId = createUser("shared-visual-limit")

        val candidateMatch = matchService.createMatch(
            userAId = sharedUserId,
            userBId = createUser("candidate-visual-limit")
        )
        chatService.startFirstChat(candidateMatch.id)
        chatService.recordChatDecision(candidateMatch.id, candidateMatch.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(candidateMatch.id, candidateMatch.userBId, ChatContinueDecision.APPROVED)
        visualReviewService.makeAvailableNowForTest(candidateMatch.id)
        saveConnectionLocks(sharedUserId, count = 4)

        visualReviewService.recordDecision(candidateMatch.id, candidateMatch.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(candidateMatch.id, candidateMatch.userBId, VisualDecision.APPROVED)

        assertEquals(MatchState.VISUAL_APPROVED, matchService.findByIdOrThrow(candidateMatch.id).state)
        assertNotNull(connectionRepository.findByMatchId(candidateMatch.id))
        assertEquals(
            5,
            lockRepository.countByUserIdAndEngagementType(sharedUserId, EngagementType.CONNECTION)
        )
    }

    @Test
    fun `replaying connection creation does not duplicate connection locks above active connection limit`() {
        val sharedUserId = createUser("shared-idempotent-limit")

        val candidateMatch = matchService.createMatch(
            userAId = sharedUserId,
            userBId = createUser("candidate-idempotent-limit")
        )
        saveConnectionLocks(sharedUserId, count = 4)

        val first = connectionService.createFromMatch(candidateMatch)
        val second = connectionService.createFromMatch(candidateMatch)

        assertEquals(first.id, second.id)
        assertEquals(
            1,
            connectionRepository.findAll().count { it.matchId == candidateMatch.id }
        )
        assertEquals(
            5,
            lockRepository.countByUserIdAndEngagementType(sharedUserId, EngagementType.CONNECTION)
        )
        assertEquals(
            1,
            lockRepository.findAll().count {
                it.userId == sharedUserId &&
                    it.engagementId == first.id &&
                    it.engagementType == EngagementType.CONNECTION
            }
        )
    }

    private fun createUser(prefix: String): UUID =
        userService.createUser("$prefix-${UUID.randomUUID()}@example.com").id

    private fun saveConnectionLocks(
        userId: UUID,
        count: Int
    ) {
        repeat(count) {
            lockRepository.save(
                ActiveEngagementLock(
                    userId = userId,
                    engagementId = UUID.randomUUID(),
                    engagementType = EngagementType.CONNECTION
                )
            )
        }
        lockRepository.flush()
    }

    private fun runConcurrently(vararg actions: () -> Unit): List<Boolean> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(actions.size)

        try {
            val futures = actions.map { action ->
                executor.submit(
                    Callable {
                        start.await()
                        try {
                            action()
                            true
                        } catch (_: RuntimeException) {
                            false
                        }
                    }
                )
            }

            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
