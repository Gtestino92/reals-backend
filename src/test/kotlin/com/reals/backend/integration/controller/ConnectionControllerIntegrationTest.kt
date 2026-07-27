package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.domain.SecondChatResolutionRequestStatus
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestPropertySource(
    properties = [
        "user-reliability.enabled=true"
    ]
)
class ConnectionControllerIntegrationTest : ControllerIT() {

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `submit proposal list returns created proposals`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        val body = mapOf(
            "expectedRoundNumber" to 1,
            "proposedDateTimes" to listOf(
                slot.toString(),
                slot.plusHours(1).toString()
            )
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].userId", equalTo(setup.userAId.toString())))
            .andExpect(jsonPath("$[0].preferenceOrder", equalTo(1)))
            .andExpect(jsonPath("$[1].preferenceOrder", equalTo(2)))
    }

    @Test
    fun `submit proposal list requires positive expected round`() {
        val setup = createConnectionInSchedulingPhase()
        val body = mapOf(
            "expectedRoundNumber" to 0,
            "proposedDateTimes" to listOf(futureHalfHourSlot().toString())
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `submit proposal list rejects old body shape`() {
        val setup = createConnectionInSchedulingPhase()
        val body = mapOf(
            "proposedDateTimes" to listOf(futureHalfHourSlot().toString())
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("MALFORMED_REQUEST")))
    }

    @Test
    fun `stale proposal submission returns scheduling round changed`() {
        val setup = createConnectionInSchedulingPhase()
        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        negotiation.roundNumber = 2
        negotiationRepository.saveAndFlush(negotiation)

        val body = mapOf(
            "expectedRoundNumber" to 1,
            "proposedDateTimes" to listOf(futureHalfHourSlot().toString())
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SCHEDULING_ROUND_CHANGED")))
    }

    @Test
    fun `matching proposal lists confirm negotiation over http`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        val body = mapOf(
            "expectedRoundNumber" to 1,
            "proposedDateTimes" to listOf(slot.toString())
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/negotiation")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo(NegotiationStatus.CONFIRMED.name)))
            .andExpect(jsonPath("$.confirmedDateTime").exists())
            .andExpect(jsonPath("$.schedulingExpiresAt").exists())
    }

    @Test
    fun `user can accept partner proposal without own proposal over http`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        val body = mapOf(
            "expectedRoundNumber" to 1,
            "proposedDateTimes" to listOf(slot.toString())
        )

        val proposalId =
            objectMapper.readTree(
                mockMvc.perform(
                    post("/api/connections/${setup.connectionId}/proposals")
                        .with(authenticatedAs(setup.userAId))
                        .contentType(jsonContentType)
                        .content(jsonBody(body))
                )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
        )[0]["id"].asString()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals/$proposalId/acceptance")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo(NegotiationStatus.CONFIRMED.name)))
            .andExpect(jsonPath("$.confirmedDateTime").exists())
            .andExpect(jsonPath("$.schedulingExpiresAt").exists())
    }

    @Test
    fun `accept expired proposal over http returns proposal not available and keeps negotiation pending`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        val body = mapOf(
            "expectedRoundNumber" to 1,
            "proposedDateTimes" to listOf(slot.toString())
        )

        val proposalId =
            java.util.UUID.fromString(
                objectMapper.readTree(
                    mockMvc.perform(
                        post("/api/connections/${setup.connectionId}/proposals")
                            .with(authenticatedAs(setup.userAId))
                            .contentType(jsonContentType)
                            .content(jsonBody(body))
                    )
                        .andExpect(status().isCreated)
                        .andReturn()
                        .response
                        .contentAsString
                )[0]["id"].asString()
            )

        val proposal = proposalRepository.findById(proposalId).orElseThrow()
        proposal.proposedDateTime = OffsetDateTime.now().minusMinutes(30)
        proposalRepository.saveAndFlush(proposal)

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals/$proposalId/acceptance")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SCHEDULING_PROPOSAL_NOT_AVAILABLE")))

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/negotiation")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo(NegotiationStatus.PENDING.name)))
            .andExpect(jsonPath("$.roundNumber", equalTo(1)))
            .andExpect(jsonPath("$.confirmedDateTime", nullValue()))
    }

    @Test
    fun `reject partner proposals after scheduling expiration returns stable code`() {
        val setup = createConnectionInSchedulingPhase()

        connectionRepository.updateSchedulingExpiresAt(
            connectionId = setup.connectionId,
            expiresAt = OffsetDateTime.now().minusSeconds(1)
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/negotiation/rejections")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("expectedRoundNumber" to 1)))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SCHEDULING_EXPIRED")))
    }

    @Test
    fun `reject partner proposals requires positive expected round`() {
        val setup = createConnectionInSchedulingPhase()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/negotiation/rejections")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("expectedRoundNumber" to 0)))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `reject partner proposals rejects old empty body shape`() {
        val setup = createConnectionInSchedulingPhase()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/negotiation/rejections")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("MALFORMED_REQUEST")))
    }

    @Test
    fun `stale partner proposal rejection returns scheduling round changed`() {
        val setup = createConnectionInSchedulingPhase()

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(futureHalfHourSlot())
        )

        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        negotiation.roundNumber = 2
        negotiationRepository.saveAndFlush(negotiation)

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/negotiation/rejections")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("expectedRoundNumber" to 1)))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SCHEDULING_ROUND_CHANGED")))
    }

    @Test
    fun `non participant cannot get connection`() {
        val setup = createConnectionInSchedulingPhase()
        val stranger = userService.createUser("connection-stranger-${java.util.UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error", equalTo("Forbidden")))
    }

    @Test
    fun `join second chat materializes active second chat over http`() {
        val setup = createScheduledSecondChatReadyToEnter()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/join")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connectionId", equalTo(setup.connectionId.toString())))
            .andExpect(jsonPath("$.chatId").exists())
            .andExpect(jsonPath("$.scheduledAt").exists())
            .andExpect(jsonPath("$.onTimeUntil").exists())
            .andExpect(jsonPath("$.entryClosesAt").exists())
            .andExpect(jsonPath("$.absoluteExpiresAt").exists())
            .andExpect(jsonPath("$.serverTime").exists())
            .andExpect(jsonPath("$.myAttendanceStatus", equalTo("ON_TIME")))
            .andExpect(jsonPath("$.partnerAttendanceStatus", equalTo("PENDING")))
            .andExpect(jsonPath("$.conversationStartedAt", nullValue()))

        assertEquals(
            1,
            chatRepository.findAll().count {
                it.connectionId == setup.connectionId && it.chatType == ChatType.SECOND_CHAT
            }
        )
        assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
    }

    @Test
    fun `join second chat repeated sequential calls return same chat over http`() {
        val setup = createScheduledSecondChatReadyToEnter()

        val first =
            objectMapper.readTree(
                mockMvc.perform(
                    post("/api/connections/${setup.connectionId}/second-chat/join")
                        .with(authenticatedAs(setup.userAId))
                )
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            )

        val second =
            objectMapper.readTree(
                mockMvc.perform(
                    post("/api/connections/${setup.connectionId}/second-chat/join")
                        .with(authenticatedAs(setup.userBId))
                )
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
        )

        assertEquals(first["chatId"].asString(), second["chatId"].asString())
        assertEquals("ON_TIME", first["myAttendanceStatus"].asString())
        assertEquals("ON_TIME", second["myAttendanceStatus"].asString())
        assertTrue(!second["conversationStartedAt"].isNull)
        assertEquals(
            1,
            chatRepository.findAll().count {
                it.connectionId == setup.connectionId && it.chatType == ChatType.SECOND_CHAT
            }
        )
        assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
    }

    @Test
    fun `second chat status exposes server time pending claim and cancellation over http`() {
        val setup = createScheduledSecondChatReadyToEnter()
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().minusMinutes(10)
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/join")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myAttendanceStatus", equalTo("LATE")))

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/second-chat/status")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.serverTime").exists())
            .andExpect(jsonPath("$.canClaimPartnerNoShow", equalTo(true)))
            .andExpect(jsonPath("$.activeNoShowClaim", nullValue()))

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/no-show-claims")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.activeNoShowClaim.status", equalTo("PENDING")))
            .andExpect(jsonPath("$.activeNoShowClaim.expiresAt").exists())

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/join")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myAttendanceStatus", equalTo("LATE")))
            .andExpect(jsonPath("$.activeNoShowClaim", nullValue()))

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/second-chat/status")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeNoShowClaim", nullValue()))
            .andExpect(jsonPath("$.partnerAttendanceStatus", equalTo("LATE")))
    }

    @Test
    fun `duplicate pending no show claim returns ok over http`() {
        val setup = createScheduledSecondChatReadyToEnter()
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().minusMinutes(10)
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/join")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/no-show-claims")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.activeNoShowClaim.status", equalTo("PENDING")))

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/no-show-claims")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeNoShowClaim.status", equalTo("PENDING")))
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `duplicate expired no show claim resolves without created over http`() {
        val setup =
            TransactionTemplate(transactionManager).execute {
                val fixture = createScheduledSecondChatReadyToEnter()
                negotiationRepository.updateConfirmedDateTimeByConnectionId(
                    connectionId = fixture.connectionId,
                    confirmedDateTime = OffsetDateTime.now().minusMinutes(10)
                )
                fixture
            }

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/join")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/no-show-claims")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isCreated)

        val forcedExpiresAt =
            TransactionTemplate(transactionManager).execute {
                secondChatResolutionRequestRepository.findAll()
                    .single { it.connectionId == setup.connectionId }
                    .also {
                        it.expiresAt = OffsetDateTime.now().minusSeconds(1).withNano(0)
                        secondChatResolutionRequestRepository.saveAndFlush(it)
                    }
                    .expiresAt
            }

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/no-show-claims")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeNoShowClaim", nullValue()))
            .andExpect(jsonPath("$.partnerAttendanceStatus", equalTo("NO_SHOW")))

        val requests =
            TransactionTemplate(transactionManager).execute {
                secondChatResolutionRequestRepository.findAll().filter { it.connectionId == setup.connectionId }
            }
        assertEquals(1, requests.size)
        assertEquals(SecondChatResolutionRequestStatus.COMPLETED, requests.single().status)
        assertEquals(forcedExpiresAt.toInstant(), requests.single().expiresAt.toInstant())
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `hard cutoff join conflict commits both absent closure over http`() {
        val setup =
            TransactionTemplate(transactionManager).execute {
                val fixture = createScheduledSecondChatReadyToEnter()
                negotiationRepository.updateConfirmedDateTimeByConnectionId(
                    connectionId = fixture.connectionId,
                    confirmedDateTime = OffsetDateTime.now().minusMinutes(20).minusSeconds(5)
                )
                fixture
            }

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/join")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_ENTRY_CLOSED")))

        val snapshot =
            TransactionTemplate(transactionManager).execute {
                val participations = secondChatParticipationRepository.findByConnectionId(setup.connectionId)
                val events = userReliabilityEventRepository.findAll().filter {
                    it.relatedConnectionId == setup.connectionId &&
                        it.eventType == UserReliabilityEventType.SECOND_CHAT_NO_SHOW
                }
                Triple(
                    connectionRepository.findById(setup.connectionId).orElseThrow().state,
                    participations.map { it.userId to it.attendanceStatus }.toMap(),
                    events.map { it.userId }.toSet()
                )
            }

        assertEquals(ConnectionState.CLOSED, snapshot.first)
        assertEquals(SecondChatAttendanceStatus.NO_SHOW, snapshot.second[setup.userAId])
        assertEquals(SecondChatAttendanceStatus.NO_SHOW, snapshot.second[setup.userBId])
        assertEquals(setOf(setup.userAId, setup.userBId), snapshot.third)
        assertEquals(null, chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT))
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `hard cutoff absent join conflict commits abandoned read only chat over http`() {
        val setup =
            TransactionTemplate(transactionManager).execute {
                val fixture = createScheduledSecondChatReadyToEnter()
                val scheduledAt = OffsetDateTime.now().minusMinutes(20).minusSeconds(5)
                negotiationRepository.updateConfirmedDateTimeByConnectionId(
                    connectionId = fixture.connectionId,
                    confirmedDateTime = scheduledAt
                )
                joinSecondChatOrThrow(
                    connectionId = fixture.connectionId,
                    userId = fixture.userAId,
                    now = scheduledAt.plusMinutes(1)
                )
                fixture
            }

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat/join")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_ENTRY_CLOSED")))

        val snapshot =
            TransactionTemplate(transactionManager).execute {
                val chat = chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT)
                    ?: error("Second chat missing")
                val absentParticipation =
                    secondChatParticipationRepository.findByConnectionIdAndUserId(setup.connectionId, setup.userBId)
                        ?: error("Absent participation missing")
                val noShowEvents = userReliabilityEventRepository.findAll().count {
                    it.userId == setup.userBId &&
                        it.relatedConnectionId == setup.connectionId &&
                        it.eventType == UserReliabilityEventType.SECOND_CHAT_NO_SHOW
                }
                listOf(
                    connectionRepository.findById(setup.connectionId).orElseThrow().state,
                    absentParticipation.attendanceStatus,
                    chat.status,
                    chat.endedReason,
                    chat.endedAt != null,
                    chat.readOnlyUntil != null,
                    noShowEvents
                )
            }

        assertEquals(ConnectionState.SECOND_CHAT, snapshot[0])
        assertEquals(SecondChatAttendanceStatus.NO_SHOW, snapshot[1])
        assertEquals(ChatStatus.ABANDONED, snapshot[2])
        assertEquals(ChatEndReason.SECOND_CHAT_NO_SHOW, snapshot[3])
        assertEquals(true, snapshot[4])
        assertEquals(true, snapshot[5])
        assertEquals(1, snapshot[6])
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `message at expired inactivity claim commits abandoned chat over http`() {
        val fixture =
            TransactionTemplate(transactionManager).execute {
                val setup = createActiveSecondChat()
                val startedAt = chatRepository.findById(setup.secondChatId).orElseThrow().conversationStartedAt!!
                val lastMessage = sendMessageOrThrow(
                    chatId = setup.secondChatId,
                    senderId = setup.userAId,
                    content = "Espero respuesta",
                    now = startedAt.plusMinutes(1)
                )
                val claim =
                    secondChatConversationLifecycleService.createPartnerInactivityClaim(
                        connectionId = setup.connectionId,
                        requesterUserId = setup.userAId,
                        now = lastMessage.sentAt.plusMinutes(5)
                    )
                claim.request!!.expiresAt = OffsetDateTime.now().minusSeconds(1)
                secondChatResolutionRequestRepository.saveAndFlush(claim.request)
                Pair(setup, chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.secondChatId).size)
            }
        val setup = fixture.first

        mockMvc.perform(
            post("/api/chats/${setup.secondChatId}/messages")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("content" to "Demasiado tarde")))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_CONVERSATION_ALREADY_RESOLVED")))

        TransactionTemplate(transactionManager).execute {
            val chat = chatRepository.findById(setup.secondChatId).orElseThrow()
            assertEquals(ChatStatus.ABANDONED, chat.status)
            assertEquals(ChatEndReason.SECOND_CHAT_PARTNER_INACTIVITY, chat.endedReason)
            assertEquals(fixture.second, chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.secondChatId).size)
            assertEquals(
                1,
                userReliabilityEventRepository.findAll().count {
                    it.relatedConnectionId == setup.connectionId &&
                        it.userId == setup.userBId &&
                        it.eventType == UserReliabilityEventType.SECOND_CHAT_ABANDONED_AFTER_JOIN
                }
            )
            assertEquals(
                0,
                userReliabilityEventRepository.findAll().count {
                    it.relatedConnectionId == setup.connectionId &&
                        it.userId == setup.userAId &&
                        it.eventType == UserReliabilityEventType.SECOND_CHAT_ABANDONED_AFTER_JOIN
                }
            )
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `completion acceptance at expired boundary commits timeout over http`() {
        val fixture =
            TransactionTemplate(transactionManager).execute {
                val setup = createActiveSecondChat()
                val startedAt = chatRepository.findById(setup.secondChatId).orElseThrow().conversationStartedAt!!
                sendMessageOrThrow(setup.secondChatId, setup.userAId, "Mensaje A", startedAt.plusMinutes(1))
                sendMessageOrThrow(setup.secondChatId, setup.userBId, "Mensaje B", startedAt.plusMinutes(2))
                val request =
                    secondChatConversationLifecycleService.createMutualCompletionRequest(
                        connectionId = setup.connectionId,
                        requesterUserId = setup.userAId,
                        now = startedAt.plusMinutes(10)
                    )
                Pair(setup, request.request!!.id)
            }
        TransactionTemplate(transactionManager).execute {
            secondChatResolutionRequestRepository.findById(fixture.second).orElseThrow()
                .also {
                    it.expiresAt = OffsetDateTime.now().minusSeconds(1)
                    secondChatResolutionRequestRepository.saveAndFlush(it)
                }
        }

        mockMvc.perform(
            post("/api/connections/${fixture.first.connectionId}/second-chat/completion-requests/${fixture.second}/decision")
                .with(authenticatedAs(fixture.first.userBId))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("decision" to "ACCEPTED")))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_COMPLETION_REQUEST_NOT_ACTIONABLE")))

        TransactionTemplate(transactionManager).execute {
            assertEquals(
                SecondChatResolutionRequestStatus.TIMED_OUT,
                secondChatResolutionRequestRepository.findById(fixture.second).orElseThrow().status
            )
            assertEquals(ChatStatus.ACTIVE, chatRepository.findById(fixture.first.secondChatId).orElseThrow().status)
            assertEquals(
                0,
                userReliabilityEventRepository.findAll().count {
                    it.relatedConnectionId == fixture.first.connectionId &&
                        it.eventType == UserReliabilityEventType.SECOND_CHAT_MUTUAL_COMPLETION
                }
            )
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `completion acceptance after due inactivity commits abandoned chat over http`() {
        val fixture =
            TransactionTemplate(transactionManager).execute {
                val setup = createActiveSecondChat()
                val serverNow = OffsetDateTime.now().withNano(0)
                val conversationStartedAt = serverNow.minusMinutes(11).minusSeconds(5)
                val chat = chatRepository.findById(setup.secondChatId).orElseThrow()
                chat.conversationStartedAt = conversationStartedAt
                chatRepository.saveAndFlush(chat)
                sendMessageOrThrow(setup.secondChatId, setup.userAId, "Mensaje A", conversationStartedAt.plusSeconds(30))
                sendMessageOrThrow(setup.secondChatId, setup.userBId, "Mensaje B", conversationStartedAt.plusMinutes(1))
                val request =
                    secondChatConversationLifecycleService.createMutualCompletionRequest(
                        connectionId = setup.connectionId,
                        requesterUserId = setup.userBId,
                        now = conversationStartedAt.plusMinutes(10).plusSeconds(30)
                    )
                Pair(setup, request.request!!.id)
            }

        mockMvc.perform(
            post("/api/connections/${fixture.first.connectionId}/second-chat/completion-requests/${fixture.second}/decision")
                .with(authenticatedAs(fixture.first.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("decision" to "ACCEPTED")))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_CONVERSATION_ALREADY_RESOLVED")))

        TransactionTemplate(transactionManager).execute {
            assertEquals(
                SecondChatResolutionRequestStatus.CANCELLED,
                secondChatResolutionRequestRepository.findById(fixture.second).orElseThrow().status
            )
            val chat = chatRepository.findById(fixture.first.secondChatId).orElseThrow()
            assertEquals(ChatStatus.ABANDONED, chat.status)
            assertEquals(ChatEndReason.SECOND_CHAT_PARTNER_INACTIVITY, chat.endedReason)
            assertEquals(
                1,
                userReliabilityEventRepository.findAll().count {
                    it.relatedConnectionId == fixture.first.connectionId &&
                        it.userId == fixture.first.userAId &&
                        it.eventType == UserReliabilityEventType.SECOND_CHAT_ABANDONED_AFTER_JOIN
                }
            )
            assertEquals(
                0,
                userReliabilityEventRepository.findAll().count {
                    it.relatedConnectionId == fixture.first.connectionId &&
                        it.eventType == UserReliabilityEventType.SECOND_CHAT_MUTUAL_COMPLETION
                }
            )
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent join second chat requests return same chat over http`() {
        val setup =
            TransactionTemplate(transactionManager).execute {
                createScheduledSecondChatReadyToEnter()
            }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures =
                listOf(setup.userAId, setup.userBId).map { userId ->
                    executor.submit(
                        Callable {
                            start.await()
                            mockMvc.perform(
                                post("/api/connections/${setup.connectionId}/second-chat/join")
                                    .with(authenticatedAs(userId))
                            )
                                .andExpect(status().isOk)
                                .andReturn()
                                .response
                                .contentAsString
                        }
                    )
                }

            start.countDown()

            val chatIds =
                futures.map {
                    objectMapper.readTree(
                        it.get(15, TimeUnit.SECONDS)
                    )["chatId"].asString()
                }

            assertEquals(1, chatIds.toSet().size)
            assertEquals(
                1,
                chatRepository.findAll().count {
                    it.connectionId == setup.connectionId && it.chatType == ChatType.SECOND_CHAT
                }
            )
            assertEquals(
                ConnectionState.SECOND_CHAT,
                connectionRepository.findById(setup.connectionId).orElseThrow().state
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `get second chat before available time returns conflict`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        schedulingService.addProposal(setup.connectionId, setup.userAId, slot, 1)
        schedulingService.addProposal(setup.connectionId, setup.userBId, slot, 1)

        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().plusMinutes(1)
        )

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/chat")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_NOT_AVAILABLE_YET")))

        assertEquals(
            null,
            chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT)
        )
    }

    @Test
    fun `get second chat in invalid connection state returns conflict`() {
        val setup = createConnectionInSchedulingPhase()

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/chat")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_NOT_AVAILABLE")))

        assertEquals(
            null,
            chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT)
        )
    }

    @Test
    fun `get second chat after expired scheduled window returns conflict`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        schedulingService.addProposal(setup.connectionId, setup.userAId, slot, 1)
        schedulingService.addProposal(setup.connectionId, setup.userBId, slot, 1)

        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().minusMinutes(121)
        )

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/chat")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_EXPIRED")))

        assertEquals(
            null,
            chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT)
        )
    }

    @Test
    fun `user can dismiss read only second chat from home`() {
        val setup = createReadOnlySecondChat()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dismissed", equalTo(true)))

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))

        assertEquals(
            1,
            connectionHomeDismissalRepository.findAll().count {
                it.userId == setup.userAId && it.connectionId == setup.connectionId
            }
        )
        assertEquals(ChatStatus.EXPIRED, chatRepository.findById(setup.secondChatId).orElseThrow().status)
    }

    @Test
    fun `second chat dismissal is user specific`() {
        val setup = createReadOnlySecondChat()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(1)))
            .andExpect(jsonPath("$.nextSteps[0].type", equalTo("SECOND_CHAT_READ_ONLY")))
            .andExpect(jsonPath("$.nextSteps[0].connectionId", equalTo(setup.connectionId.toString())))
    }

    @Test
    fun `second chat dismissal endpoint is idempotent`() {
        val setup = createReadOnlySecondChat()

        repeat(2) {
            mockMvc.perform(
                post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                    .with(authenticatedAs(setup.userAId))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.dismissed", equalTo(true)))
        }

        assertEquals(
            1,
            connectionHomeDismissalRepository.findAll().count {
                it.userId == setup.userAId && it.connectionId == setup.connectionId
            }
        )
    }

    @Test
    fun `second chat dismissal rejects actionable second chat`() {
        val setup = createActiveSecondChat()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("DOMAIN_CONFLICT")))

        assertFalse(
            connectionHomeDismissalRepository.existsByUserIdAndConnectionId(
                userId = setup.userAId,
                connectionId = setup.connectionId
            )
        )
    }

    @Test
    fun `second chat dismissal rejects non participant`() {
        val setup = createReadOnlySecondChat()
        val stranger = userService.createUser("dismiss-stranger-${java.util.UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error", equalTo("Forbidden")))
    }

    @Test
    fun `proposal validation bad request is returned with stable error code`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        val body = mapOf(
            "expectedRoundNumber" to 1,
            "proposedDateTimes" to listOf(
                slot.toString(),
                slot.plusHours(1).toString(),
                slot.plusHours(2).toString(),
                slot.plusHours(3).toString()
            )
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("SCHEDULING_INVALID_PROPOSALS")))
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }

    private fun createReadOnlySecondChat() =
        createActiveSecondChat()
            .also {
                chatRepository.updateTimeoutAt(
                    chatId = it.secondChatId,
                    timeoutAt = OffsetDateTime.now().minusSeconds(1)
                )
                chatService.expireSecondChatToReadOnly(it.secondChatId)
            }
}
