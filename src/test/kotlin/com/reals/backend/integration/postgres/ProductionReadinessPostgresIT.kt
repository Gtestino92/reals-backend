package com.reals.backend.integration.postgres

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Gender
import com.reals.backend.domain.User
import com.reals.backend.service.MeHomeService
import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

class ProductionReadinessPostgresIT : PostgresITBase() {

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var meHomeService: MeHomeService

    @Test
    fun `home query-count baseline stays bounded on postgres`() {
        val oneMatchUserId = createUserWithActiveFirstChats("pg-home-one", 1)
        homeStatusService.getOrCreateStatus(oneMatchUserId)

        val manyMatchesUserId = createUserWithActiveFirstChats("pg-home-many", 3)
        homeStatusService.getOrCreateStatus(manyMatchesUserId)

        val fullOne = measurePreparedStatements("postgres home full one active first chat") {
            meHomeService.getHome(oneMatchUserId)
        }
        val fullMany = measurePreparedStatements("postgres home full three active first chats") {
            meHomeService.getHome(manyMatchesUserId)
        }
        val pendingOne = measurePreparedStatements("postgres home pending one active first chat") {
            meHomeService.getPendingHomeState(oneMatchUserId)
        }
        val pendingMany = measurePreparedStatements("postgres home pending three active first chats") {
            meHomeService.getPendingHomeState(manyMatchesUserId)
        }

        println(
            "PostgreSQL Home query-count baseline: " +
                "fullOne=$fullOne, fullMany=$fullMany, " +
                "pendingOne=$pendingOne, pendingMany=$pendingMany"
        )

        assertTrue(
            fullMany <= fullOne + 2,
            "full Home should remain bounded: one=$fullOne many=$fullMany"
        )
        assertTrue(
            pendingMany <= pendingOne + 2,
            "pending Home should remain bounded: one=$pendingOne many=$pendingMany"
        )
    }

    @Test
    fun `postgres message-read baseline uses bounded pages and stable cursor ordering`() {
        val setup = createMatchWithFirstChat("pg-message-baseline")
        val baseTime = OffsetDateTime.parse("2026-07-17T12:00:00Z")
        val inserted = insertMessages(
            chatId = setup.firstChatId,
            senderAId = setup.userAId,
            senderBId = setup.userBId,
            count = 5_000,
            baseTime = baseTime
        )
        val tieMessages = insertTieMessages(
            chatId = setup.firstChatId,
            senderAId = setup.userAId,
            baseTime = baseTime.plusHours(2)
        )

        chatService.getMessages(setup.firstChatId, setup.userAId, limit = 200)
        chatService.getMessagesAfter(setup.firstChatId, setup.userAId, inserted[100].id, limit = 50)

        lateinit var initial: List<ChatMessage>
        lateinit var incremental: com.reals.backend.service.ChatService.ChatMessagesPage
        val initialNanos = measureNanoTime {
            initial = chatService.getMessages(setup.firstChatId, setup.userAId, limit = 200)
        }
        val incrementalNanos = measureNanoTime {
            incremental = chatService.getMessagesAfter(
                chatId = setup.firstChatId,
                userId = setup.userAId,
                afterMessageId = inserted[100].id,
                limit = 50
            )
        }

        val tiePage = chatService.getMessagesAfter(
            chatId = setup.firstChatId,
            userId = setup.userAId,
            afterMessageId = tieMessages[1].id,
            limit = 3
        )

        val migrationVersion = jdbcTemplate.queryForObject(
            "select version from flyway_schema_history where success = true order by installed_rank desc limit 1",
            String::class.java
        )
        val indexName = jdbcTemplate.queryForObject(
            "select to_regclass('idx_chat_messages_session_sent_at_id')::text",
            String::class.java
        )
        val plan = jdbcTemplate.queryForList(
            """
            explain select *
            from chat_messages
            where chat_session_id = ?
            order by sent_at desc, id desc
            limit 200
            """.trimIndent(),
            String::class.java,
            setup.firstChatId
        ).joinToString(" | ")

        println(
            "PostgreSQL message-read baseline: " +
                "dataset=${inserted.size + tieMessages.size}, " +
                "initialPage=${initial.size}, incrementalPage=${incremental.messages.size}, " +
                "initial=${Duration.ofNanos(initialNanos).toMillis()}ms, " +
                "incremental=${Duration.ofNanos(incrementalNanos).toMillis()}ms, " +
                "flywayVersion=$migrationVersion, index=$indexName, plan=$plan"
        )

        assertEquals("30", migrationVersion)
        assertEquals("idx_chat_messages_session_sent_at_id", indexName)
        assertEquals(200, initial.size)
        assertTrue(initial.zipWithNext().all { (left, right) -> left.sentAt <= right.sentAt })
        assertEquals(50, incremental.messages.size)
        assertTrue(incremental.hasMore)
        assertEquals(tieMessages.drop(2).map { it.id }, tiePage.messages.map { it.id })
        assertFalse(tiePage.hasMore)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent messages in one first chat persist on postgres`() {
        val setup = createMatchWithFirstChat("pg-concurrent-first")

        val outcomes = runMessagesConcurrently(
            { chatService.sendMessage(setup.firstChatId, setup.userAId, "first postgres concurrent message") },
            { chatService.sendMessage(setup.firstChatId, setup.userBId, "second postgres concurrent message") }
        )

        assertTrue(outcomes.all { it.value != null }, outcomes.toString())
        val messages = chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.firstChatId)
        assertEquals(2, messages.size)
        assertEquals(
            setOf("first postgres concurrent message", "second postgres concurrent message"),
            messages.map { it.content }.toSet()
        )
        assertEquals(messages.maxOf { it.sentAt }, chatRepository.findById(setup.firstChatId).orElseThrow().lastMessageAt)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent activation of one available second chat materializes once on postgres`() {
        val setup = createAvailableSecondChat()

        val outcomes = runMessagesConcurrently(
            { chatService.sendMessage(setup.secondChatId, setup.userAId, "postgres available second from A") },
            { chatService.sendMessage(setup.secondChatId, setup.userBId, "postgres available second from B") }
        )

        assertTrue(outcomes.all { it.value != null }, outcomes.toString())
        val messages = chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.secondChatId)
        val chat = chatRepository.findById(setup.secondChatId).orElseThrow()
        assertEquals(2, messages.size)
        assertEquals(
            setOf("postgres available second from A", "postgres available second from B"),
            messages.map { it.content }.toSet()
        )
        assertEquals(1, chatRepository.findAll().count {
            it.connectionId == setup.connectionId && it.chatType == ChatType.SECOND_CHAT
        })
        assertEquals(ChatStatus.ACTIVE, chat.status)
        assertNotNull(chat.activatedAt)
        assertEquals(ConnectionState.SECOND_CHAT, connectionRepository.findById(setup.connectionId).orElseThrow().state)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent firebase provisioning converges to one user on postgres`() {
        val firebaseUid = "pg-firebase-${UUID.randomUUID()}"
        val email = "pg-firebase-${UUID.randomUUID()}@example.com"

        val outcomes = runProvisioningConcurrently(
            { userService.provisionFromFirebase(firebaseUid, email) },
            { userService.provisionFromFirebase(firebaseUid, email.uppercase()) }
        )

        assertTrue(outcomes.all { it.value != null }, outcomes.toString())
        assertEquals(1, outcomes.map { it.value!!.id }.toSet().size)
        val linkedUser = userRepository.findByFirebaseUid(firebaseUid)
        assertNotNull(linkedUser)
        assertEquals(firebaseUid, linkedUser!!.firebaseUid)
        assertEquals(email, linkedUser.email)
        assertEquals(1, userRepository.findAll().count { it.firebaseUid == firebaseUid })
    }

    private fun measurePreparedStatements(
        label: String,
        operation: () -> Unit
    ): Long {
        entityManager.flush()
        entityManager.clear()

        val statistics = entityManager
            .entityManagerFactory
            .unwrap(SessionFactory::class.java)
            .statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        operation()

        val count = statistics.prepareStatementCount
        println("$label prepared statements: $count")
        return count
    }

    private fun createUserWithActiveFirstChats(
        label: String,
        matchCount: Int
    ): UUID {
        val userId = createMeasuredUser("$label-anchor")
        repeat(matchCount) { index ->
            val partnerId = createMeasuredPartner("$label-partner-$index")
            val match = matchService.createMatch(userId, partnerId)
            chatService.startFirstChat(match.id)
        }
        return userId
    }

    private fun createMeasuredUser(label: String): UUID =
        createActiveProfile(
            email = "$label-${UUID.randomUUID()}@example.com",
            displayName = "Postgres Home $label",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

    private fun createMeasuredPartner(label: String): UUID =
        createActiveProfile(
            email = "$label-${UUID.randomUUID()}@example.com",
            displayName = "Postgres Home $label",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

    private fun insertMessages(
        chatId: UUID,
        senderAId: UUID,
        senderBId: UUID,
        count: Int,
        baseTime: OffsetDateTime
    ): List<InsertedMessage> {
        entityManager.flush()
        val messages = (0 until count).map { index ->
            InsertedMessage(
                id = UUID.randomUUID(),
                sentAt = baseTime.plusSeconds(index.toLong())
            )
        }
        jdbcTemplate.batchUpdate(
            """
            insert into chat_messages (id, chat_session_id, sender_id, content, sent_at)
            values (?, ?, ?, ?, ?)
            """.trimIndent(),
            messages.mapIndexed { index, message ->
                arrayOf(
                    message.id,
                    chatId,
                    if (index % 2 == 0) senderAId else senderBId,
                    "postgres bulk message $index",
                    Timestamp.from(message.sentAt.toInstant())
                )
            }
        )
        return messages
    }

    private fun insertTieMessages(
        chatId: UUID,
        senderAId: UUID,
        baseTime: OffsetDateTime
    ): List<InsertedMessage> {
        entityManager.flush()
        val orderedIds = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000102"),
            UUID.fromString("00000000-0000-0000-0000-000000000103"),
            UUID.fromString("00000000-0000-0000-0000-000000000104"),
            UUID.fromString("00000000-0000-0000-0000-000000000105")
        )
        val messages = orderedIds.map { InsertedMessage(id = it, sentAt = baseTime) }
        jdbcTemplate.batchUpdate(
            """
            insert into chat_messages (id, chat_session_id, sender_id, content, sent_at)
            values (?, ?, ?, ?, ?)
            """.trimIndent(),
            messages.mapIndexed { index, message ->
                arrayOf(
                    message.id,
                    chatId,
                    senderAId,
                    "postgres tie message $index",
                    Timestamp.from(message.sentAt.toInstant())
                )
            }
        )
        return messages
    }

    private fun createAvailableSecondChat(): ActiveSecondChatFixture =
        TransactionTemplate(transactionManager).execute {
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
                connectionId = setup.connectionId,
                secondChatId = chat.id
            )
        }

    private fun runMessagesConcurrently(vararg actions: () -> ChatMessage): List<ConcurrentMessageOutcome> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(actions.size)

        try {
            val futures = actions.map { action ->
                executor.submit(
                    Callable {
                        start.await()
                        try {
                            ConcurrentMessageOutcome(value = action(), throwable = null)
                        } catch (ex: Throwable) {
                            ConcurrentMessageOutcome(value = null, throwable = ex)
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

    private fun runProvisioningConcurrently(vararg actions: () -> User): List<ConcurrentProvisioningOutcome> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(actions.size)

        try {
            val futures = actions.map { action ->
                executor.submit(
                    Callable {
                        start.await()
                        try {
                            ConcurrentProvisioningOutcome(value = action(), throwable = null)
                        } catch (ex: Throwable) {
                            ConcurrentProvisioningOutcome(value = null, throwable = ex)
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

    private data class InsertedMessage(
        val id: UUID,
        val sentAt: OffsetDateTime
    )

    private data class ActiveSecondChatFixture(
        val userAId: UUID,
        val userBId: UUID,
        val connectionId: UUID,
        val secondChatId: UUID
    )

    private data class ConcurrentMessageOutcome(
        val value: ChatMessage?,
        val throwable: Throwable?
    )

    private data class ConcurrentProvisioningOutcome(
        val value: User?,
        val throwable: Throwable?
    )
}
