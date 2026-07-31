package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatDecision
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.domain.UserHomeStatus
import com.reals.backend.domain.VisualReview
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionHomeDismissalRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.matching.MatchmakingAvailability
import com.reals.backend.service.matching.MatchmakingAvailabilityService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

class MeHomeServiceTest {

    private val profileRepository = Mockito.mock(ProfileRepository::class.java)
    private val queueRepository = Mockito.mock(MatchmakingQueueRepository::class.java)
    private val matchRepository = Mockito.mock(MatchRepository::class.java)
    private val chatRepository = Mockito.mock(ChatRepository::class.java)
    private val connectionRepository = Mockito.mock(ConnectionRepository::class.java)
    private val dismissalRepository = Mockito.mock(ConnectionHomeDismissalRepository::class.java)
    private val negotiationRepository = Mockito.mock(ScheduleNegotiationRepository::class.java)
    private val visualReviewRepository = Mockito.mock(VisualReviewRepository::class.java)
    private val chatDecisionRepository = Mockito.mock(ChatDecisionRepository::class.java)
    private val matchmakingAvailabilityService = Mockito.mock(MatchmakingAvailabilityService::class.java)
    private val homeStatusService = Mockito.mock(HomeStatusService::class.java)
    private val userBlockService = Mockito.mock(UserBlockService::class.java)
    private val readMetrics = ReadMetrics(SimpleMeterRegistry())

    private val service = MeHomeService(
        profileRepository = profileRepository,
        queueRepository = queueRepository,
        matchRepository = matchRepository,
        chatRepository = chatRepository,
        connectionRepository = connectionRepository,
        dismissalRepository = dismissalRepository,
        negotiationRepository = negotiationRepository,
        visualReviewRepository = visualReviewRepository,
        chatDecisionRepository = chatDecisionRepository,
        matchmakingAvailabilityService = matchmakingAvailabilityService,
        homeStatusService = homeStatusService,
        userBlockService = userBlockService,
        readMetrics = readMetrics,
        secondChatDurationMinutes = 120
    )

    @Test
    fun `full home batches chat decisions once for several active first chat matches`() {
        val userId = UUID.randomUUID()
        val matches = activeFirstChatMatches(userId, count = 3)
        val matchIds = matches.map { it.id }
        stubOperationalState(userId, matches)
        Mockito.`when`(queueRepository.existsByUserId(userId)).thenReturn(false)
        Mockito.`when`(matchmakingAvailabilityService.availabilityFor(userId, false))
            .thenReturn(MatchmakingAvailability(canSearch = true, blockedReason = null))
        Mockito.`when`(chatDecisionRepository.findByMatchIdIn(matchIds))
            .thenReturn(
                listOf(
                    ChatDecision(
                        chatId = chatFor(matches[0]).id,
                        matchId = matches[0].id,
                        userADecision = ChatContinueDecision.APPROVED
                    )
                )
            )

        val response = service.getHome(userId)

        assertEquals(2, response.pendingActions.size)
        Mockito.verify(chatDecisionRepository, Mockito.times(1)).findByMatchIdIn(matchIds)
        Mockito.verifyNoMoreInteractions(chatDecisionRepository)
    }

    @Test
    fun `pending home uses same batched decision loading and preserves missing decision behavior`() {
        val userId = UUID.randomUUID()
        val matches = activeFirstChatMatches(userId, count = 2)
        val matchIds = matches.map { it.id }
        stubOperationalState(userId, matches)
        Mockito.`when`(homeStatusService.getOrCreateStatus(userId))
            .thenReturn(UserHomeStatus(userId = userId, version = 7, dirty = true))
        Mockito.`when`(chatDecisionRepository.findByMatchIdIn(matchIds)).thenReturn(emptyList())

        val response = service.getPendingHomeState(userId)

        assertEquals(7, response.version)
        assertEquals(2, response.pendingActions.size)
        Mockito.verify(chatDecisionRepository, Mockito.times(1)).findByMatchIdIn(matchIds)
        Mockito.verifyNoMoreInteractions(chatDecisionRepository)
    }

    @Test
    fun `home snapshot skips decision query when no active chat matches exist`() {
        val userId = UUID.randomUUID()
        stubOperationalState(userId, emptyList())
        Mockito.`when`(queueRepository.existsByUserId(userId)).thenReturn(false)
        Mockito.`when`(matchmakingAvailabilityService.availabilityFor(userId, false))
            .thenReturn(MatchmakingAvailability(canSearch = true, blockedReason = null))

        service.getHome(userId)
        Mockito.verifyNoInteractions(chatDecisionRepository)

        Mockito.reset(chatDecisionRepository)
        Mockito.`when`(homeStatusService.getOrCreateStatus(userId))
            .thenReturn(UserHomeStatus(userId = userId, version = 0, dirty = false))

        service.getPendingHomeState(userId)
        Mockito.verifyNoInteractions(chatDecisionRepository)
    }

    @Test
    fun `home orders current second chats before scheduled scheduling and read only entries`() {
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val active = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000010"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT
        )
        val scheduled = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000020"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT_SCHEDULED
        )
        val scheduling = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000030"),
            userId = userId,
            state = ConnectionState.SCHEDULING_PHASE,
            updatedAt = now.plusMinutes(10)
        )
        val readOnly = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000040"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT
        )
        val activeChat = secondChat(
            connection = active,
            status = ChatStatus.ACTIVE,
            availableAt = now.minusMinutes(1),
            timeoutAt = now.plusMinutes(119)
        )
        val readOnlyChat = secondChat(
            connection = readOnly,
            status = ChatStatus.EXPIRED,
            availableAt = now.minusHours(2),
            timeoutAt = now.minusMinutes(1),
            readOnlyUntil = now.plusHours(1)
        )

        stubOperationalState(
            userId = userId,
            matches = emptyList(),
            connections = listOf(readOnly, scheduling, scheduled, active),
            secondChats = listOf(activeChat, readOnlyChat),
            negotiations = listOf(
                negotiation(active, now.minusMinutes(1)),
                negotiation(scheduled, now.plusMinutes(30)),
                negotiation(readOnly, now.minusHours(2))
            )
        )
        stubFullHome(userId)
        Mockito.`when`(homeStatusService.getOrCreateStatus(userId))
            .thenReturn(UserHomeStatus(userId = userId, version = 1, dirty = true))

        val fullOrder = service.getHome(userId).nextSteps.map { it.connectionId }
        val pendingOrder = service.getPendingHomeState(userId).nextSteps.map { it.connectionId }

        assertEquals(listOf(active.id, scheduled.id, scheduling.id, readOnly.id), fullOrder)
        assertEquals(fullOrder, pendingOrder)
    }

    @Test
    fun `home orders scheduled second chats by confirmed time then stable id`() {
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val later = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000022"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT_SCHEDULED
        )
        val tieSecond = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000021"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT_SCHEDULED
        )
        val tieFirst = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000020"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT_SCHEDULED
        )
        val earlier = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000019"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT_SCHEDULED
        )
        val tiedStart = now.plusMinutes(20)

        stubOperationalState(
            userId = userId,
            matches = emptyList(),
            connections = listOf(later, tieSecond, earlier, tieFirst),
            negotiations = listOf(
                negotiation(later, now.plusMinutes(40)),
                negotiation(tieSecond, tiedStart),
                negotiation(tieFirst, tiedStart),
                negotiation(earlier, now.plusMinutes(10))
            )
        )
        stubFullHome(userId)
        Mockito.`when`(homeStatusService.getOrCreateStatus(userId))
            .thenReturn(UserHomeStatus(userId = userId, version = 1, dirty = true))

        val fullOrder = service.getHome(userId).nextSteps.map { it.connectionId }
        val pendingOrder = service.getPendingHomeState(userId).nextSteps.map { it.connectionId }

        assertEquals(listOf(earlier.id, tieFirst.id, tieSecond.id, later.id), fullOrder)
        assertEquals(fullOrder, pendingOrder)
    }

    @Test
    fun `home orders current second chats by most recent available time first`() {
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val older = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000051"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT
        )
        val recent = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000052"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT
        )

        stubOperationalState(
            userId = userId,
            matches = emptyList(),
            connections = listOf(older, recent),
            secondChats = listOf(
                secondChat(
                    connection = older,
                    status = ChatStatus.ACTIVE,
                    availableAt = now.minusMinutes(10),
                    timeoutAt = now.plusMinutes(110)
                ),
                secondChat(
                    connection = recent,
                    status = ChatStatus.ACTIVE,
                    availableAt = now.minusMinutes(1),
                    timeoutAt = now.plusMinutes(119)
                )
            )
        )
        stubFullHome(userId)
        Mockito.`when`(homeStatusService.getOrCreateStatus(userId))
            .thenReturn(UserHomeStatus(userId = userId, version = 1, dirty = true))

        val fullOrder = service.getHome(userId).nextSteps.map { it.connectionId }
        val pendingOrder = service.getPendingHomeState(userId).nextSteps.map { it.connectionId }

        assertEquals(listOf(recent.id, older.id), fullOrder)
        assertEquals(fullOrder, pendingOrder)
    }

    @Test
    fun `home orders read only second chats by earliest read only expiry first`() {
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val laterExpiry = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000061"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT
        )
        val soonerExpiry = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000062"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT
        )

        stubOperationalState(
            userId = userId,
            matches = emptyList(),
            connections = listOf(laterExpiry, soonerExpiry),
            secondChats = listOf(
                secondChat(
                    connection = laterExpiry,
                    status = ChatStatus.EXPIRED,
                    availableAt = now.minusHours(3),
                    timeoutAt = now.minusHours(1),
                    readOnlyUntil = now.plusMinutes(30)
                ),
                secondChat(
                    connection = soonerExpiry,
                    status = ChatStatus.EXPIRED,
                    availableAt = now.minusHours(4),
                    timeoutAt = now.minusHours(2),
                    readOnlyUntil = now.plusMinutes(10)
                )
            )
        )
        stubFullHome(userId)
        Mockito.`when`(homeStatusService.getOrCreateStatus(userId))
            .thenReturn(UserHomeStatus(userId = userId, version = 1, dirty = true))

        val fullOrder = service.getHome(userId).nextSteps.map { it.connectionId }
        val pendingOrder = service.getPendingHomeState(userId).nextSteps.map { it.connectionId }

        assertEquals(listOf(soonerExpiry.id, laterExpiry.id), fullOrder)
        assertEquals(fullOrder, pendingOrder)
    }

    @Test
    fun `home orders missing second chat timestamps after valid timestamps`() {
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val missingTimestamp = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000071"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT_AVAILABLE
        )
        val validTimestamp = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000072"),
            userId = userId,
            state = ConnectionState.SECOND_CHAT_AVAILABLE
        )

        stubOperationalState(
            userId = userId,
            matches = emptyList(),
            connections = listOf(missingTimestamp, validTimestamp),
            negotiations = listOf(negotiation(validTimestamp, now.minusMinutes(1)))
        )
        stubFullHome(userId)
        Mockito.`when`(homeStatusService.getOrCreateStatus(userId))
            .thenReturn(UserHomeStatus(userId = userId, version = 1, dirty = true))

        val fullOrder = service.getHome(userId).nextSteps.map { it.connectionId }
        val pendingOrder = service.getPendingHomeState(userId).nextSteps.map { it.connectionId }

        assertEquals(listOf(validTimestamp.id, missingTimestamp.id), fullOrder)
        assertEquals(fullOrder, pendingOrder)
    }

    @Test
    fun `home orders pending actions by nearest expiration`() {
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val firstLater = match(
            id = UUID.fromString("00000000-0000-0000-0000-000000000101"),
            userId = userId,
            state = MatchState.CHAT_ACTIVE
        )
        val visualSoon = match(
            id = UUID.fromString("00000000-0000-0000-0000-000000000102"),
            userId = userId,
            state = MatchState.VISUAL_PHASE
        )
        val firstMiddle = match(
            id = UUID.fromString("00000000-0000-0000-0000-000000000103"),
            userId = userId,
            state = MatchState.CHAT_ACTIVE
        )
        val matches = listOf(firstLater, visualSoon, firstMiddle)
        val firstChatMatchIds = listOf(firstLater.id, firstMiddle.id)

        stubOperationalState(
            userId = userId,
            matches = matches,
            firstChats = listOf(
                chatFor(firstLater, timeoutAt = now.plusMinutes(30)),
                chatFor(firstMiddle, timeoutAt = now.plusMinutes(15))
            ),
            visualReviews = listOf(visualReview(visualSoon, expiresAt = now.plusMinutes(5)))
        )
        stubFullHome(userId)
        Mockito.`when`(homeStatusService.getOrCreateStatus(userId))
            .thenReturn(UserHomeStatus(userId = userId, version = 1, dirty = true))
        Mockito.`when`(chatDecisionRepository.findByMatchIdIn(firstChatMatchIds)).thenReturn(emptyList())

        val fullOrder = service.getHome(userId).pendingActions.map { it.matchId }
        val pendingOrder = service.getPendingHomeState(userId).pendingActions.map { it.matchId }

        assertEquals(listOf(visualSoon.id, firstMiddle.id, firstLater.id), fullOrder)
        assertEquals(fullOrder, pendingOrder)
    }

    @Test
    fun `home orders scheduling next steps by nearest expiration then stable id`() {
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val later = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000203"),
            userId = userId,
            state = ConnectionState.SCHEDULING_PHASE,
            schedulingExpiresAt = now.plusMinutes(30)
        )
        val tieSecond = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000202"),
            userId = userId,
            state = ConnectionState.SCHEDULING_PHASE,
            schedulingExpiresAt = now.plusMinutes(10)
        )
        val tieFirst = connection(
            id = UUID.fromString("00000000-0000-0000-0000-000000000201"),
            userId = userId,
            state = ConnectionState.SCHEDULING_PHASE,
            schedulingExpiresAt = now.plusMinutes(10)
        )

        stubOperationalState(
            userId = userId,
            matches = emptyList(),
            connections = listOf(later, tieSecond, tieFirst)
        )
        stubFullHome(userId)
        Mockito.`when`(homeStatusService.getOrCreateStatus(userId))
            .thenReturn(UserHomeStatus(userId = userId, version = 1, dirty = true))

        val fullOrder = service.getHome(userId).nextSteps.map { it.connectionId }
        val pendingOrder = service.getPendingHomeState(userId).nextSteps.map { it.connectionId }

        assertEquals(listOf(tieFirst.id, tieSecond.id, later.id), fullOrder)
        assertEquals(fullOrder, pendingOrder)
    }

    private fun stubOperationalState(
        userId: UUID,
        matches: List<Match>,
        connections: List<com.reals.backend.domain.Connection> = emptyList(),
        secondChats: List<Chat> = emptyList(),
        negotiations: List<ScheduleNegotiation> = emptyList(),
        firstChats: List<Chat>? = null,
        visualReviews: List<VisualReview> = emptyList()
    ) {
        Mockito.`when`(userBlockService.findBlockedCounterpartUserIds(userId)).thenReturn(emptySet())
        Mockito.`when`(
            matchRepository.findByParticipantIdAndStateIn(
                userId,
                listOf(MatchState.CHAT_ACTIVE, MatchState.VISUAL_PHASE)
            )
        ).thenReturn(matches)
        Mockito.`when`(
            chatRepository.findByMatchIdInAndChatType(
                matches.map { it.id },
                ChatType.FIRST_CHAT
            )
        ).thenReturn(firstChats ?: matches.map { chatFor(it) })
        val visualMatches = matches.filter { it.state == MatchState.VISUAL_PHASE }
        if (visualMatches.isNotEmpty()) {
            Mockito.`when`(visualReviewRepository.findByMatchIdIn(visualMatches.map { it.id }))
                .thenReturn(visualReviews)
        }
        if (matches.isNotEmpty()) {
            Mockito.`when`(profileRepository.findByUserIdIn(matches.map { it.userBId }))
                .thenReturn(emptyList())
        }
        val partnerUserIds = matches.map { it.userBId } + connections.map { it.userBId }
        if (partnerUserIds.isNotEmpty()) {
            Mockito.`when`(profileRepository.findByUserIdIn(partnerUserIds))
                .thenReturn(emptyList())
        }
        Mockito.`when`(
            connectionRepository.findByParticipantIdAndStateIn(
                userId,
                listOf(
                    ConnectionState.SCHEDULING_PHASE,
                    ConnectionState.SECOND_CHAT_SCHEDULED,
                    ConnectionState.SECOND_CHAT_AVAILABLE,
                    ConnectionState.SECOND_CHAT
                )
            )
        ).thenReturn(connections)
        Mockito.`when`(
            connectionRepository.findByParticipantIdAndStateIn(
                userId,
                listOf(ConnectionState.SCHEDULING_PENDING)
            )
        ).thenReturn(emptyList())
        if (connections.isNotEmpty()) {
            Mockito.`when`(
                dismissalRepository.findDismissedConnectionIds(
                    userId = userId,
                    connectionIds = connections.map { it.id }
                )
            ).thenReturn(emptyList())
            Mockito.`when`(
                chatRepository.findByConnectionIdInAndChatType(
                    connectionIds = connections.map { it.id },
                    chatType = ChatType.SECOND_CHAT
                )
            ).thenReturn(secondChats)
            Mockito.`when`(negotiationRepository.findByConnectionIdIn(connections.map { it.id }))
                .thenReturn(negotiations)
        }
    }

    private fun stubFullHome(userId: UUID) {
        Mockito.`when`(queueRepository.existsByUserId(userId)).thenReturn(false)
        Mockito.`when`(matchmakingAvailabilityService.availabilityFor(userId, false))
            .thenReturn(MatchmakingAvailability(canSearch = true, blockedReason = null))
    }

    private fun activeFirstChatMatches(
        userId: UUID,
        count: Int
    ): List<Match> =
        (0 until count).map { index ->
            Match(
                userAId = userId,
                userBId = UUID.randomUUID(),
                state = MatchState.CHAT_ACTIVE,
                updatedAt = OffsetDateTime.now().minusMinutes(index.toLong())
            )
        }

    private fun match(
        id: UUID,
        userId: UUID,
        state: MatchState
    ): Match =
        Match(
            id = id,
            userAId = userId,
            userBId = UUID.nameUUIDFromBytes("match-partner:$id".toByteArray()),
            state = state
        )

    private fun chatFor(
        match: Match,
        timeoutAt: OffsetDateTime = OffsetDateTime.now().plusMinutes(10)
    ): Chat =
        Chat(
            id = UUID.nameUUIDFromBytes(match.id.toString().toByteArray()),
            matchId = match.id,
            chatType = ChatType.FIRST_CHAT,
            status = ChatStatus.ACTIVE,
            startedAt = OffsetDateTime.now().minusMinutes(1),
            timeoutAt = timeoutAt
        )

    private fun connection(
        id: UUID,
        userId: UUID,
        state: ConnectionState,
        updatedAt: OffsetDateTime = OffsetDateTime.now(),
        schedulingExpiresAt: OffsetDateTime = updatedAt.plusHours(1)
    ): com.reals.backend.domain.Connection =
        com.reals.backend.domain.Connection(
            id = id,
            matchId = UUID.nameUUIDFromBytes("match:$id".toByteArray()),
            userAId = userId,
            userBId = UUID.nameUUIDFromBytes("partner:$id".toByteArray()),
            state = state,
            schedulingExpiresAt = schedulingExpiresAt,
            updatedAt = updatedAt
        )

    private fun visualReview(
        match: Match,
        expiresAt: OffsetDateTime
    ): VisualReview =
        VisualReview(
            matchId = match.id,
            expiresAt = expiresAt
        )

    private fun secondChat(
        connection: com.reals.backend.domain.Connection,
        status: ChatStatus,
        availableAt: OffsetDateTime,
        timeoutAt: OffsetDateTime,
        readOnlyUntil: OffsetDateTime? = null
    ): Chat =
        Chat(
            matchId = connection.matchId,
            connectionId = connection.id,
            chatType = ChatType.SECOND_CHAT,
            status = status,
            startedAt = availableAt,
            availableAt = availableAt,
            timeoutAt = timeoutAt,
            endedAt = if (readOnlyUntil == null) null else timeoutAt,
            readOnlyUntil = readOnlyUntil
        )

    private fun negotiation(
        connection: com.reals.backend.domain.Connection,
        confirmedDateTime: OffsetDateTime
    ): ScheduleNegotiation =
        ScheduleNegotiation(
            connectionId = connection.id,
            status = NegotiationStatus.CONFIRMED,
            confirmedDateTime = confirmedDateTime
        )
}
