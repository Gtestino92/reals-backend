package com.reals.backend.integration.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChatMessageConcurrencyIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `two concurrent messages in same active first chat both persist`() {
        val setup = createMatchWithFirstChat("concurrent-first")

        val outcomes = runConcurrently(
            { chatService.sendMessage(setup.firstChatId, setup.userAId, "first concurrent message") },
            { chatService.sendMessage(setup.firstChatId, setup.userBId, "second concurrent message") }
        )

        assertTrue(outcomes.all { it.value != null }, outcomes.toString())
        val messages = chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.firstChatId)
        assertEquals(2, messages.size)
        assertEquals(
            setOf("first concurrent message", "second concurrent message"),
            messages.map { it.content }.toSet()
        )
        assertEquals(messages.maxOf { it.sentAt }, chatRepository.findById(setup.firstChatId).orElseThrow().lastMessageAt)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `two concurrent messages in same available second chat activate once and both persist`() {
        val setup = createAvailableSecondChat()

        val outcomes = runConcurrently(
            { chatService.sendMessage(setup.secondChatId, setup.userAId, "available second from A") },
            { chatService.sendMessage(setup.secondChatId, setup.userBId, "available second from B") }
        )

        assertTrue(outcomes.all { it.value != null }, outcomes.toString())
        val messages = chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.secondChatId)
        val chat = chatRepository.findById(setup.secondChatId).orElseThrow()
        assertEquals(2, messages.size)
        assertEquals(setOf("available second from A", "available second from B"), messages.map { it.content }.toSet())
        assertEquals(1, chatRepository.findAll().count {
            it.connectionId == setup.connectionId && it.chatType == ChatType.SECOND_CHAT
        })
        assertEquals(ChatStatus.ACTIVE, chat.status)
        assertNotNull(chat.activatedAt)
        assertEquals(ConnectionState.SECOND_CHAT, connectionRepository.findById(setup.connectionId).orElseThrow().state)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `messages in different chats both complete independently`() {
        val first = createMatchWithFirstChat("concurrent-independent-a")
        val second = createMatchWithFirstChat("concurrent-independent-b")

        val outcomes = runConcurrently(
            { chatService.sendMessage(first.firstChatId, first.userAId, "chat one message") },
            { chatService.sendMessage(second.firstChatId, second.userAId, "chat two message") }
        )

        assertTrue(outcomes.all { it.value != null }, outcomes.toString())
        assertEquals(1, chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(first.firstChatId).size)
        assertEquals(1, chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(second.firstChatId).size)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `send concurrent with terminal transition has stable messages and terminal rejection type`() {
        val setup = createMatchWithFirstChat("concurrent-terminal")

        chatService.endChat(
            chatId = setup.firstChatId,
            finalStatus = ChatStatus.EXPIRED,
            endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
        )

        val outcomes = runConcurrently(
            { chatService.sendMessage(setup.firstChatId, setup.userAId, "should not persist twice") },
            { chatService.sendMessage(setup.firstChatId, setup.userBId, "should also fail") }
        )

        assertTrue(outcomes.all { it.throwable is DomainConflictException }, outcomes.toString())
        outcomes.forEach {
            assertEquals(DomainErrorCode.CHAT_EXPIRED, (it.throwable as DomainConflictException).code)
        }
        assertEquals(0, chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.firstChatId).size)
        assertEquals(ChatStatus.EXPIRED, chatRepository.findById(setup.firstChatId).orElseThrow().status)
    }

    private fun createAvailableSecondChat(): ActiveSecondChatFixture {
        return TransactionTemplate(transactionManager).execute {
            val setup = createScheduledSecondChatReadyToEnter()
            connectionService.transitionToSecondChatAvailable(setup.connectionId)
            val availableAt = OffsetDateTime.now().minusSeconds(1)
            val chat = chatRepository.saveAndFlush(
                Chat(
                    matchId = setup.matchId,
                    connectionId = setup.connectionId,
                    chatType = ChatType.SECOND_CHAT,
                    status = ChatStatus.AVAILABLE,
                    startedAt = availableAt,
                    availableAt = availableAt,
                    timeoutAt = availableAt.plusHours(1)
                )
            )

            ActiveSecondChatFixture(
                userAId = setup.userAId,
                userBId = setup.userBId,
                matchId = setup.matchId,
                connectionId = setup.connectionId,
                secondChatId = chat.id
            )
        }
    }

    private fun runConcurrently(vararg actions: () -> ChatMessage): List<ConcurrentOutcome> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(actions.size)

        try {
            val futures = actions.map { action ->
                executor.submit(
                    Callable {
                        start.await()
                        try {
                            ConcurrentOutcome(value = action(), throwable = null)
                        } catch (ex: Throwable) {
                            ConcurrentOutcome(value = null, throwable = ex)
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

    private data class ConcurrentOutcome(
        val value: ChatMessage?,
        val throwable: Throwable?
    )
}
