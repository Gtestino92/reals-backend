package com.reals.backend.integration.service

import com.reals.backend.integration.BaseIT
import com.reals.backend.service.MeHomeService
import com.reals.backend.service.ReadMetrics
import com.reals.backend.service.exception.DomainNotFoundException
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class ReadMetricsIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var meHomeService: MeHomeService

    @BeforeEach
    fun clearReadMeters() {
        meterRegistry.meters
            .filter { it.id.name.startsWith("reals.") }
            .forEach { meterRegistry.remove(it) }
    }

    @Test
    fun `home reads record variant and outcome tags`() {
        val userId = createActiveProfile(
            email = "metrics-home-${UUID.randomUUID()}@example.com",
            displayName = "Metrics Home",
            gender = com.reals.backend.domain.Gender.FEMALE,
            lookingForGenders = setOf(com.reals.backend.domain.Gender.MALE)
        )

        homeStatusService.getOrCreateStatus(userId)

        val fullResponse = meHomeService.getHome(userId)
        assertNotNull(fullResponse)
        val pendingResponse = meHomeService.getPendingHomeState(userId)
        assertNotNull(pendingResponse)

        assertEquals(
            1,
            meterRegistry.find(ReadMetrics.HOME_LOAD)
                .tags("variant", "full", "outcome", "success")
                .timer()
                ?.count()
        )
        assertEquals(
            1,
            meterRegistry.find(ReadMetrics.HOME_LOAD)
                .tags("variant", "pending", "outcome", "success")
                .timer()
                ?.count()
        )
    }

    @Test
    fun `chat message reads record mode and returned counts`() {
        val setup = createMatchWithFirstChat("metrics-chat")
        val first = chatService.sendMessage(setup.firstChatId, setup.userAId, "Primer mensaje")
        chatService.sendMessage(setup.firstChatId, setup.userBId, "Segundo mensaje")

        val initial = chatService.getMessages(
            chatId = setup.firstChatId,
            userId = setup.userAId,
            limit = 1
        )
        val incremental = chatService.getMessagesAfter(
            chatId = setup.firstChatId,
            userId = setup.userAId,
            afterMessageId = first.id,
            limit = 1
        )

        assertEquals(1, initial.size)
        assertEquals(1, incremental.messages.size)
        assertEquals(
            1,
            meterRegistry.find(ReadMetrics.CHAT_MESSAGES_READ)
                .tags("mode", "initial", "outcome", "success")
                .timer()
                ?.count()
        )
        assertEquals(
            1,
            meterRegistry.find(ReadMetrics.CHAT_MESSAGES_READ)
                .tags("mode", "incremental", "outcome", "success")
                .timer()
                ?.count()
        )
        assertEquals(
            1.0,
            meterRegistry.find(ReadMetrics.CHAT_MESSAGES_RETURNED)
                .tags("mode", "initial")
                .summary()
                ?.totalAmount()
        )
        assertEquals(
            1.0,
            meterRegistry.find(ReadMetrics.CHAT_MESSAGES_RETURNED)
                .tags("mode", "incremental")
                .summary()
                ?.totalAmount()
        )
    }

    @Test
    fun `read metric records error outcome and propagates original exception`() {
        val userId = userService.createUser("metrics-error-${UUID.randomUUID()}@example.com").id

        assertThrows(DomainNotFoundException::class.java) {
            chatService.getMessages(
                chatId = UUID.randomUUID(),
                userId = userId,
                limit = 1
            )
        }

        assertEquals(
            1,
            meterRegistry.find(ReadMetrics.CHAT_MESSAGES_READ)
                .tags("mode", "initial", "outcome", "error")
                .timer()
                ?.count()
        )
        assertEquals(
            null,
            meterRegistry.find(ReadMetrics.CHAT_MESSAGES_RETURNED)
                .tags("mode", "initial")
                .summary()
        )
    }

    @Test
    fun `custom read metrics do not register high cardinality tag keys`() {
        val setup = createMatchWithFirstChat("metrics-cardinality")
        chatService.sendMessage(setup.firstChatId, setup.userAId, "Mensaje")
        chatService.getMessages(setup.firstChatId, setup.userAId, limit = 1)
        meHomeService.getHome(setup.userAId)

        val allowedTagKeys = setOf("variant", "outcome", "mode")
        val actualTagKeys = meterRegistry.meters
            .filter { it.id.name.startsWith("reals.") }
            .flatMap { meter -> meter.id.tags.map { it.key } }
            .toSet()

        assertTrue(actualTagKeys.isNotEmpty())
        assertTrue(actualTagKeys.all { it in allowedTagKeys })
    }
}
