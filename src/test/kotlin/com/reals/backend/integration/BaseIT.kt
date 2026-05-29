package com.reals.backend.integration

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.VisualDecision
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.ScheduleProposalRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.scheduler.ScheduledSecondChatStartJob
import com.reals.backend.service.ChatExitService
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.MatchService
import com.reals.backend.service.MatchmakingService
import com.reals.backend.service.ProfileService
import com.reals.backend.service.SchedulingService
import com.reals.backend.service.UserService
import com.reals.backend.service.VisualReviewService
import org.junit.jupiter.api.Assertions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@Transactional
abstract class BaseIT {

    @Autowired
    protected lateinit var userService: UserService

    @Autowired
    protected lateinit var profileService: ProfileService

    @Autowired
    protected lateinit var matchmakingService: MatchmakingService

    @Autowired
    protected lateinit var matchService: MatchService

    @Autowired
    protected lateinit var chatService: ChatService

    @Autowired
    protected lateinit var chatExitService: ChatExitService

    @Autowired
    protected lateinit var visualReviewService: VisualReviewService

    @Autowired
    protected lateinit var connectionService: ConnectionService

    @Autowired
    protected lateinit var schedulingService: SchedulingService

    @Autowired
    protected lateinit var lockRepository: ActiveEngagementLockRepository

    @Autowired
    protected lateinit var chatDecisionRepository: ChatDecisionRepository

    @Autowired
    protected lateinit var chatRepository: ChatRepository

    @Autowired
    protected lateinit var connectionRepository: ConnectionRepository

    @Autowired
    protected lateinit var matchRepository: MatchRepository

    @Autowired
    protected lateinit var negotiationRepository: ScheduleNegotiationRepository

    @Autowired
    protected lateinit var proposalRepository: ScheduleProposalRepository

    @Autowired
    protected lateinit var penaltyRepository: PenaltyRepository

    @Autowired
    protected lateinit var visualReviewRepository: VisualReviewRepository

    protected fun createActiveProfile(
        email: String,
        displayName: String,
        gender: Gender,
        lookingForGender: LookingForGender,
        intention: Intention = Intention.DATE
    ): UUID {
        val user = userService.createUser(email)
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = displayName,
            birthDate = LocalDate.of(1995, 1, 1),
            gender = gender,
            lookingForGender = lookingForGender,
            intention = intention,
            city = "Buenos Aires",
            country = "AR",
            bio = "Integration test profile"
        )

        repeat(4) { index ->
            profileService.addPhoto(
                profileId = profile.id,
                url = "https://example.com/${profile.id}-${index + 1}.jpg",
                position = index + 1,
                isPersonPhoto = index == 0,
                isFullBody = index == 0
            )
        }

        profileService.activateProfile(profile.id)
        return user.id
    }

    protected fun createMatchWithFirstChat(
        emailPrefix: String = "match"
    ): MatchFixture {
        val userA = createActiveProfile(
            email = "$emailPrefix-a-${UUID.randomUUID()}@example.com",
            displayName = "Match A",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val userB = createActiveProfile(
            email = "$emailPrefix-b-${UUID.randomUUID()}@example.com",
            displayName = "Match B",
            gender = Gender.MALE,
            lookingForGender = LookingForGender.WOMEN
        )

        val match = matchService.createMatch(userA, userB)
        val chat = chatService.startFirstChat(match.id)

        return MatchFixture(
            userAId = userA,
            userBId = userB,
            matchId = match.id,
            firstChatId = chat.id
        )
    }

    protected fun createMatchInVisualPhase(): MatchFixture {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        return setup
    }

    protected fun createConnectionInSchedulingPhase(): ConnectionFixture {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")

        return ConnectionFixture(
            userAId = setup.userAId,
            userBId = setup.userBId,
            matchId = setup.matchId,
            connectionId = connection.id
        )
    }

    protected fun createAvailableSecondChat(): ConnectionFixture {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = slot
        )
        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTime = slot
        )
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().minusSeconds(1)
        )

        ScheduledSecondChatStartJob(
            negotiationRepository = negotiationRepository,
            connectionService = connectionService,
            chatService = chatService
        ).run()

        return setup
    }

    protected fun createActiveSecondChat(): ActiveSecondChatFixture {
        val setup = createAvailableSecondChat()
        val secondChat =
            chatRepository.findByConnectionIdAndChatType(
                setup.connectionId,
                ChatType.SECOND_CHAT
            ) ?: error("Second chat was not made available")

        chatService.findVisibleSecondChatOrThrow(
            connectionId = setup.connectionId,
            userId = setup.userAId
        )

        Assertions.assertEquals(ChatStatus.ACTIVE, chatService.findByIdOrThrow(secondChat.id).status)
        Assertions.assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionService.findByIdOrThrow(setup.connectionId).state
        )

        return ActiveSecondChatFixture(
            userAId = setup.userAId,
            userBId = setup.userBId,
            matchId = setup.matchId,
            connectionId = setup.connectionId,
            secondChatId = secondChat.id
        )
    }

    protected fun futureHalfHourSlot(): OffsetDateTime {
        val candidate = OffsetDateTime.now()
            .plusDays(1)
            .withSecond(0)
            .withNano(0)

        return if (candidate.minute < 30) {
            candidate.withMinute(30)
        } else {
            candidate.plusHours(1).withMinute(0)
        }
    }

    protected fun assertNoMatchLocks(
        userAId: UUID,
        userBId: UUID
    ) {
        Assertions.assertEquals(0, lockRepository.countByUserIdAndEngagementType(userAId, EngagementType.MATCH))
        Assertions.assertEquals(0, lockRepository.countByUserIdAndEngagementType(userBId, EngagementType.MATCH))
    }

    protected fun assertNoConnectionLocks(
        userAId: UUID,
        userBId: UUID
    ) {
        Assertions.assertEquals(0, lockRepository.countByUserIdAndEngagementType(userAId, EngagementType.CONNECTION))
        Assertions.assertEquals(0, lockRepository.countByUserIdAndEngagementType(userBId, EngagementType.CONNECTION))
    }

    protected fun assertNoActivePenalties(
        userAId: UUID,
        userBId: UUID
    ) {
        Assertions.assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(userAId))
        Assertions.assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(userBId))
    }

    protected fun matchExistsForUsers(
        userAId: UUID,
        userBId: UUID
    ): Boolean =
        matchRepository.findAll().any {
            (it.userAId == userAId && it.userBId == userBId) ||
                (it.userAId == userBId && it.userBId == userAId)
        }

    protected data class MatchFixture(
        val userAId: UUID,
        val userBId: UUID,
        val matchId: UUID,
        val firstChatId: UUID
    )

    protected data class ConnectionFixture(
        val userAId: UUID,
        val userBId: UUID,
        val matchId: UUID,
        val connectionId: UUID
    )

    protected data class ActiveSecondChatFixture(
        val userAId: UUID,
        val userBId: UUID,
        val matchId: UUID,
        val connectionId: UUID,
        val secondChatId: UUID
    )
}