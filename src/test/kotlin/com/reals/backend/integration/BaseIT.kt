package com.reals.backend.integration

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.MatchmakingCandidatePair
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.VisualDecision
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.AuditEventRepository
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatExitRequestRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionHomeDismissalRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.FirstChatGuidanceRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.ScheduleProposalRepository
import com.reals.backend.repository.SafetyReportEvidenceSnapshotRepository
import com.reals.backend.repository.SafetyReportRepository
import com.reals.backend.repository.SecondChatParticipationRepository
import com.reals.backend.repository.SecondChatResolutionRequestRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.PushDeviceTokenRepository
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.repository.UserBlockRepository
import com.reals.backend.repository.UserHomeStatusRepository
import com.reals.backend.repository.UserReliabilityEventRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.ChatExitService
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.FirstChatGuidanceService
import com.reals.backend.service.FirstChatGuidedQuestionCatalog
import com.reals.backend.service.HomeStatusService
import com.reals.backend.service.MatchService
import com.reals.backend.service.AuditEventService
import com.reals.backend.service.matching.MatchmakingProcessorService
import com.reals.backend.service.matching.MatchmakingPairEligibilityService
import com.reals.backend.service.matching.MatchmakingService
import com.reals.backend.repository.matching.MatchmakingCandidateRepository
import com.reals.backend.repository.matching.MatchmakingPairEligibilityRepository
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.ProfileService
import com.reals.backend.service.PushDeviceTokenService
import com.reals.backend.service.SchedulingService
import com.reals.backend.service.SecondChatConversationLifecycleService
import com.reals.backend.service.SecondChatLifecycleService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.reports.SafetyReportService
import com.reals.backend.service.reports.SafetyReportEvidenceSnapshotService
import com.reals.backend.service.UserService
import com.reals.backend.service.UserBlockCommandService
import com.reals.backend.service.UserBlockService
import com.reals.backend.service.VisualReviewService
import com.reals.backend.service.reliability.UserReliabilityScoreService
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

    protected companion object {
        const val BUENOS_AIRES_LATITUDE = -34.6037
        const val BUENOS_AIRES_LONGITUDE = -58.3816
    }

    @Autowired
    protected lateinit var userService: UserService

    @Autowired
    protected lateinit var userBlockService: UserBlockService

    @Autowired
    protected lateinit var userBlockCommandService: UserBlockCommandService

    @Autowired
    protected lateinit var profileService: ProfileService

    @Autowired
    protected lateinit var matchmakingService: MatchmakingService

    @Autowired
    protected lateinit var matchmakingProcessorService: MatchmakingProcessorService

    @Autowired
    protected lateinit var matchmakingPairEligibilityService: MatchmakingPairEligibilityService

    @Autowired
    protected lateinit var matchmakingCandidateRepository: MatchmakingCandidateRepository

    @Autowired
    protected lateinit var matchmakingPairEligibilityRepository: MatchmakingPairEligibilityRepository

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
    protected lateinit var firstChatGuidanceService: FirstChatGuidanceService

    @Autowired
    protected lateinit var firstChatGuidedQuestionCatalog: FirstChatGuidedQuestionCatalog

    @Autowired
    protected lateinit var schedulingService: SchedulingService

    @Autowired
    protected lateinit var secondChatLifecycleService: SecondChatLifecycleService

    @Autowired
    protected lateinit var secondChatConversationLifecycleService: SecondChatConversationLifecycleService

    @Autowired
    protected lateinit var safetyReportService: SafetyReportService

    @Autowired
    protected lateinit var auditEventService: AuditEventService

    @Autowired
    protected lateinit var safetyReportEvidenceSnapshotService: SafetyReportEvidenceSnapshotService

    @Autowired
    protected lateinit var penaltyService: PenaltyService

    @Autowired
    protected lateinit var pushDeviceTokenService: PushDeviceTokenService

    @Autowired
    protected lateinit var homeStatusService: HomeStatusService

    @Autowired
    protected lateinit var userReliabilityScoreService: UserReliabilityScoreService

    @Autowired
    protected lateinit var lockRepository: ActiveEngagementLockRepository

    @Autowired
    protected lateinit var chatDecisionRepository: ChatDecisionRepository

    @Autowired
    protected lateinit var chatExitRequestRepository: ChatExitRequestRepository

    @Autowired
    protected lateinit var chatMessageRepository: ChatMessageRepository

    @Autowired
    protected lateinit var chatRepository: ChatRepository

    @Autowired
    protected lateinit var connectionRepository: ConnectionRepository

    @Autowired
    protected lateinit var firstChatGuidanceRepository: FirstChatGuidanceRepository

    @Autowired
    protected lateinit var connectionHomeDismissalRepository: ConnectionHomeDismissalRepository

    @Autowired
    protected lateinit var matchRepository: MatchRepository

    @Autowired
    protected lateinit var matchmakingQueueRepository: MatchmakingQueueRepository

    @Autowired
    protected lateinit var negotiationRepository: ScheduleNegotiationRepository

    @Autowired
    protected lateinit var proposalRepository: ScheduleProposalRepository

    @Autowired
    protected lateinit var secondChatParticipationRepository: SecondChatParticipationRepository

    @Autowired
    protected lateinit var secondChatResolutionRequestRepository: SecondChatResolutionRequestRepository

    @Autowired
    protected lateinit var penaltyRepository: PenaltyRepository

    @Autowired
    protected lateinit var auditEventRepository: AuditEventRepository

    @Autowired
    protected lateinit var safetyReportRepository: SafetyReportRepository

    @Autowired
    protected lateinit var safetyReportEvidenceSnapshotRepository: SafetyReportEvidenceSnapshotRepository

    @Autowired
    protected lateinit var visualReviewRepository: VisualReviewRepository

    @Autowired
    protected lateinit var userRepository: UserRepository

    @Autowired
    protected lateinit var userBlockRepository: UserBlockRepository

    @Autowired
    protected lateinit var homeStatusRepository: UserHomeStatusRepository

    @Autowired
    protected lateinit var userReliabilityEventRepository: UserReliabilityEventRepository

    @Autowired
    protected lateinit var profileRepository: ProfileRepository

    @Autowired
    protected lateinit var profilePhotoRepository: ProfilePhotoRepository

    @Autowired
    protected lateinit var pushDeviceTokenRepository: PushDeviceTokenRepository

    @Autowired
    protected lateinit var pushNotificationDeliveryRepository: PushNotificationDeliveryRepository

    protected fun createActiveProfile(
        email: String,
        displayName: String,
        gender: Gender,
        lookingForGenders: Set<Gender>,
        intention: Intention = Intention.DATE,
        birthDate: LocalDate = LocalDate.of(1995, 1, 1),
        preferredMinAge: Int = 18,
        preferredMaxAge: Int = 99,
        maxDistanceKm: Int = 50
    ): UUID {
        val user = userService.createUser(email)
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = displayName,
            birthDate = birthDate,
            gender = gender,
            lookingForGenders = lookingForGenders,
            intention = intention,
            city = "Buenos Aires",
            countryCode = "AR",
            bio = "Integration test profile",
            preferredMinAge = preferredMinAge,
            preferredMaxAge = preferredMaxAge,
            maxDistanceKm = maxDistanceKm
        )

        repeat(4) { index ->
            profilePhotoRepository.save(
                ProfilePhoto(
                    profileId = profile.id,
                    storageProvider = PhotoStorageProvider.S3,
                    storageBucket = "reals-profile-photos-test",
                    storageKey = "users/${user.id}/profile-photos/${profile.id}-${index + 1}.jpg",
                    position = index + 1,
                    isPersonPhoto = index == 0,
                    isFullBody = index == 0,
                    validationStatus = PhotoValidationStatus.VALIDATED,
                    moderationStatus = PhotoModerationStatus.APPROVED
                )
            )
        }

        profileService.activateProfile(profile.id)
        return user.id
    }

    protected fun enqueueForMatchmaking(
        userId: UUID,
        latitude: Double = BUENOS_AIRES_LATITUDE,
        longitude: Double = BUENOS_AIRES_LONGITUDE,
        accuracyMeters: Int? = 50
    ) {
        matchmakingService.enqueue(
            userId = userId,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters
        )
    }

    protected fun findBasicCompatiblePairs(
        limit: Int = 5,
        today: LocalDate = LocalDate.now(),
        now: OffsetDateTime = OffsetDateTime.now()
    ): List<MatchmakingCandidatePair> {
        val exclusionPolicy = matchmakingPairEligibilityService.effectiveExclusionPolicy()
        val previousPairingCutoff =
            if (exclusionPolicy.excludeHistoricalPairings) {
                matchmakingPairEligibilityService.previousPairingCutoff(now)
            } else {
                null
            }
        val firstChatExpirationCutoff =
            if (exclusionPolicy.excludeHistoricalPairings) {
                matchmakingPairEligibilityService.firstChatExpirationCutoff(now)
            } else {
                null
            }
        val anchor =
            matchmakingCandidateRepository.claimNextEligibleAnchorForUpdate(
                today = today,
                exclusionPolicy = exclusionPolicy,
                previousPairingCutoff = previousPairingCutoff,
                firstChatExpirationCutoff = firstChatExpirationCutoff
            )
                ?: return emptyList()

        return matchmakingCandidateRepository.findEligiblePartnerCandidates(
            anchorQueueEntryId = anchor.queueEntryId,
            limit = limit,
            today = today,
            exclusionPolicy = exclusionPolicy,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff
        ).map { it.pair }
    }

    protected fun createMatchWithFirstChat(
        emailPrefix: String = "match"
    ): MatchFixture {
        val userA = createActiveProfile(
            email = "$emailPrefix-a-${UUID.randomUUID()}@example.com",
            displayName = "Match A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "$emailPrefix-b-${UUID.randomUUID()}@example.com",
            displayName = "Match B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
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

        connectionRepository.updateSchedulingAvailableAt(
            connectionId = connection.id,
            availableAt = OffsetDateTime.now().minusSeconds(1)
        )
        connectionService.activateScheduling(connection.id)
        schedulingService.initializeNegotiation(connection.id)

        return ConnectionFixture(
            userAId = setup.userAId,
            userBId = setup.userBId,
            matchId = setup.matchId,
            connectionId = connection.id
        )
    }

    protected fun createScheduledSecondChatReadyToEnter(): ConnectionFixture {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )
        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().minusSeconds(1)
        )

        return setup
    }

    protected fun createActiveSecondChat(): ActiveSecondChatFixture {
        val setup = createScheduledSecondChatReadyToEnter()
        val joined = joinSecondChatOrThrow(
            connectionId = setup.connectionId,
            userId = setup.userAId
        )
        joinSecondChatOrThrow(
            connectionId = setup.connectionId,
            userId = setup.userBId
        )
        val secondChat = chatRepository.findById(joined.chatId!!).orElseThrow()

        Assertions.assertEquals(ChatStatus.ACTIVE, secondChat.status)
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

    protected fun joinSecondChatOrThrow(
        connectionId: UUID,
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): SecondChatLifecycleService.SecondChatAttendanceView =
        when (
            val result = secondChatLifecycleService.joinSecondChat(
                connectionId = connectionId,
                userId = userId,
                now = now
            )
        ) {
            is SecondChatLifecycleService.SecondChatJoinResult.Joined -> result.view
            is SecondChatLifecycleService.SecondChatJoinResult.Rejected ->
                throw DomainConflictException(code = result.code, message = result.message)
        }

    protected fun rejectSecondChatJoin(
        connectionId: UUID,
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): SecondChatLifecycleService.SecondChatJoinResult.Rejected {
        val result = try {
            secondChatLifecycleService.joinSecondChat(
                connectionId = connectionId,
                userId = userId,
                now = now
            )
        } catch (ex: DomainConflictException) {
            return SecondChatLifecycleService.SecondChatJoinResult.Rejected(
                code = ex.code,
                message = ex.message ?: "Second-chat join rejected"
            )
        }
        Assertions.assertTrue(result is SecondChatLifecycleService.SecondChatJoinResult.Rejected)
        return result as SecondChatLifecycleService.SecondChatJoinResult.Rejected
    }

    protected fun sendMessageOrThrow(
        chatId: UUID,
        senderId: UUID,
        content: String,
        now: OffsetDateTime = OffsetDateTime.now()
    ): ChatMessage =
        when (
            val result = chatService.sendMessageWithResult(
                chatId = chatId,
                senderId = senderId,
                content = content,
                now = now
            )
        ) {
            is ChatService.SendMessageResult.Sent -> result.message
            is ChatService.SendMessageResult.RejectedAfterResolution ->
                throw DomainConflictException(code = result.code, message = result.message)
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
