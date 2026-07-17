package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatDecision
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.UserHomeStatus
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

    private fun stubOperationalState(
        userId: UUID,
        matches: List<Match>
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
        ).thenReturn(matches.map { chatFor(it) })
        if (matches.isNotEmpty()) {
            Mockito.`when`(profileRepository.findByUserIdIn(matches.map { it.userBId }))
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
        ).thenReturn(emptyList())
        Mockito.`when`(
            connectionRepository.findByParticipantIdAndStateIn(
                userId,
                listOf(ConnectionState.SCHEDULING_PENDING)
            )
        ).thenReturn(emptyList())
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

    private fun chatFor(match: Match): Chat =
        Chat(
            id = UUID.nameUUIDFromBytes(match.id.toString().toByteArray()),
            matchId = match.id,
            chatType = ChatType.FIRST_CHAT,
            status = ChatStatus.ACTIVE,
            startedAt = OffsetDateTime.now().minusMinutes(1),
            timeoutAt = OffsetDateTime.now().plusMinutes(10)
        )
}
