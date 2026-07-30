package com.reals.backend.integration.service

import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.MatchmakingProcessResult
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.matching.MatchmakingCandidateRepository
import com.reals.backend.repository.matching.MatchmakingPairExclusionPolicy
import com.reals.backend.service.MatchService
import com.reals.backend.service.UserBlockService
import com.reals.backend.service.matching.MatchmakingProcessorService
import com.reals.backend.service.matching.MatchmakingService
import com.reals.backend.service.ProfileService
import com.reals.backend.service.UserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MatchmakingPostgresConcurrencyIntegrationTest {

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var profileService: ProfileService

    @Autowired
    private lateinit var matchmakingService: MatchmakingService

    @Autowired
    private lateinit var matchmakingProcessorService: MatchmakingProcessorService

    @Autowired
    private lateinit var matchService: MatchService

    @Autowired
    private lateinit var matchRepository: MatchRepository

    @Autowired
    private lateinit var activeEngagementLockRepository: ActiveEngagementLockRepository

    @Autowired
    private lateinit var profilePhotoRepository: ProfilePhotoRepository

    @Autowired
    private lateinit var matchmakingQueueRepository: MatchmakingQueueRepository

    @Autowired
    private lateinit var matchmakingCandidateRepository: MatchmakingCandidateRepository

    @Autowired
    private lateinit var userBlockService: UserBlockService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute(
            """
            TRUNCATE TABLE
                active_engagement_locks,
                chats,
                chat_decisions,
                chat_exit_requests,
                chat_messages,
                connections,
                visual_reviews,
                matches,
                matchmaking_queue,
                profile_photos,
                profile_looking_for_genders,
                profiles,
                penalties,
                user_blocks,
                user_home_status,
                users
            RESTART IDENTITY CASCADE
            """.trimIndent()
        )
    }

    @Test
    fun `concurrent processors do not double match queued users on postgres`() {
        createActiveProfile(
            email = "postgres-concurrency-female-a@example.com",
            displayName = "Female A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        ).also(::enqueueForMatchmaking)
        createActiveProfile(
            email = "postgres-concurrency-male-a@example.com",
            displayName = "Male A",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        ).also(::enqueueForMatchmaking)
        createActiveProfile(
            email = "postgres-concurrency-female-b@example.com",
            displayName = "Female B",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        ).also(::enqueueForMatchmaking)
        createActiveProfile(
            email = "postgres-concurrency-male-b@example.com",
            displayName = "Male B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        ).also(::enqueueForMatchmaking)

        val results = processConcurrently(workerCount = 2)

        assertEquals(0, results.sumOf { it.failedPairs })

        val concurrentlyCreated = results.sumOf { it.matchesCreated }
        assertEquals(2, concurrentlyCreated)
        assertMatchedUsersAreDistinct()

        matchmakingProcessorService.process(maxPairsPerRun = 2)

        assertMatchedUsersAreDistinct(expectedUserCount = 4)
        assertEquals(2, matchRepository.count())
        assertEquals(0L, matchmakingQueueRepository.count())
    }

    @Test
    fun `oldest unmatchable queued user does not block later compatible pair on postgres`() {
        val oldUnmatchable = createActiveProfile(
            email = "postgres-unmatchable-old@example.com",
            displayName = "Old Unmatchable",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.NON_BINARY)
        ).also(::enqueueForMatchmaking)
        val compatibleA = createActiveProfile(
            email = "postgres-progress-compatible-a@example.com",
            displayName = "Progress Compatible A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        ).also(::enqueueForMatchmaking)
        val compatibleB = createActiveProfile(
            email = "postgres-progress-compatible-b@example.com",
            displayName = "Progress Compatible B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        ).also(::enqueueForMatchmaking)

        val result = matchmakingProcessorService.process(maxPairsPerRun = 1)

        assertEquals(1, result.matchesCreated)
        assertTrue(matchmakingQueueRepository.existsByUserId(oldUnmatchable))
        assertTrue(
            matchRepository.findAll().any {
                (it.userAId == compatibleA && it.userBId == compatibleB) ||
                    (it.userAId == compatibleB && it.userBId == compatibleA)
            }
        )
    }

    @Test
    fun `distance filtering happens before partner candidate limit on postgres`() {
        val anchor = createActiveProfile(
            email = "postgres-distance-anchor@example.com",
            displayName = "Distance Anchor",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            maxDistanceKm = 50
        ).also(::enqueueForMatchmaking)

        repeat(5) { index ->
            createActiveProfile(
                email = "postgres-distance-far-$index@example.com",
                displayName = "Distance Far $index",
                gender = Gender.MALE,
                lookingForGenders = setOf(Gender.FEMALE),
                maxDistanceKm = 50
            ).also {
                enqueueForMatchmaking(
                    userId = it,
                    latitude = 0.0,
                    longitude = 0.0
                )
            }
        }

        val nearby = createActiveProfile(
            email = "postgres-distance-near@example.com",
            displayName = "Distance Near",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE),
            maxDistanceKm = 50
        ).also(::enqueueForMatchmaking)

        val result = matchmakingProcessorService.process(maxPairsPerRun = 1)

        assertEquals(1, result.matchesCreated)
        assertTrue(
            matchRepository.findAll().any {
                (it.userAId == anchor && it.userBId == nearby) ||
                    (it.userAId == nearby && it.userBId == anchor)
            }
        )
    }

    @Test
    fun `exact partner claim ignores deleted and re-enqueued partner rows on postgres`() {
        val userA = createActiveProfile(
            email = "postgres-exact-claim-a@example.com",
            displayName = "Exact Claim A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        ).also(::enqueueForMatchmaking)
        val userB = createActiveProfile(
            email = "postgres-exact-claim-b@example.com",
            displayName = "Exact Claim B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        ).also(::enqueueForMatchmaking)

        var oldPartnerQueueEntryId: UUID? = null
        TransactionTemplate(transactionManager).executeWithoutResult {
            val anchor = matchmakingCandidateRepository.claimNextEligibleAnchorForUpdate(
                today = LocalDate.now(),
                exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
                previousPairingCutoff = null,
                firstChatExpirationCutoff = null
            ) ?: error("Expected anchor")
            val partner = matchmakingCandidateRepository.findEligiblePartnerCandidates(
                anchorQueueEntryId = anchor.queueEntryId,
                limit = 10,
                today = LocalDate.now(),
                exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
                previousPairingCutoff = null,
                firstChatExpirationCutoff = null
            ).single()
            oldPartnerQueueEntryId = partner.partnerQueueEntryId

            assertNotNull(
                matchmakingCandidateRepository.tryClaimEligiblePartnerForUpdate(
                    anchorQueueEntryId = anchor.queueEntryId,
                    partnerQueueEntryId = partner.partnerQueueEntryId,
                    today = LocalDate.now(),
                    exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
                    previousPairingCutoff = null,
                    firstChatExpirationCutoff = null
                )
            )
        }

        matchmakingService.dequeue(userB)
        enqueueForMatchmaking(userB)

        TransactionTemplate(transactionManager).executeWithoutResult {
            val anchor = matchmakingQueueRepository.findByUserId(userA) ?: error("Expected queued anchor")
            val currentPartner = matchmakingQueueRepository.findByUserId(userB) ?: error("Expected requeued partner")

            assertNull(
                matchmakingCandidateRepository.tryClaimEligiblePartnerForUpdate(
                    anchorQueueEntryId = anchor.id,
                    partnerQueueEntryId = oldPartnerQueueEntryId ?: error("Expected old partner queue entry id"),
                    today = LocalDate.now(),
                    exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
                    previousPairingCutoff = null,
                    firstChatExpirationCutoff = null
                )
            )
            assertNotNull(
                matchmakingCandidateRepository.tryClaimEligiblePartnerForUpdate(
                    anchorQueueEntryId = anchor.id,
                    partnerQueueEntryId = currentPartner.id,
                    today = LocalDate.now(),
                    exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
                    previousPairingCutoff = null,
                    firstChatExpirationCutoff = null
                )
            )
        }
    }

    @Test
    fun `partner claim returns null after pair becomes blocked on postgres`() {
        val userA = createActiveProfile(
            email = "postgres-claim-block-a@example.com",
            displayName = "Claim Block A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        ).also(::enqueueForMatchmaking)
        val userB = createActiveProfile(
            email = "postgres-claim-block-b@example.com",
            displayName = "Claim Block B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        ).also(::enqueueForMatchmaking)

        val anchor = matchmakingQueueRepository.findByUserId(userA) ?: error("Expected anchor")
        val partner = matchmakingQueueRepository.findByUserId(userB) ?: error("Expected partner")
        userBlockService.blockUser(
            blockerUserId = userB,
            blockedUserId = userA,
            source = com.reals.backend.domain.UserBlockSource.MANUAL
        )

        TransactionTemplate(transactionManager).executeWithoutResult {
            assertNull(
                matchmakingCandidateRepository.tryClaimEligiblePartnerForUpdate(
                    anchorQueueEntryId = anchor.id,
                    partnerQueueEntryId = partner.id,
                    today = LocalDate.now(),
                    exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
                    previousPairingCutoff = null,
                    firstChatExpirationCutoff = null
                )
            )
        }
    }

    @Test
    fun `concurrent direct match creation creates one active pair on postgres`() {
        val userA = createActiveProfile(
            email = "postgres-duplicate-a@example.com",
            displayName = "Duplicate A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "postgres-duplicate-b@example.com",
            displayName = "Duplicate B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        val results =
            runConcurrently(
                { matchService.createMatch(userA, userB) },
                { matchService.createMatch(userA, userB) }
            )

        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
        assertEquals(
            1,
            matchRepository.findAll().count {
                (it.userAId == userA && it.userBId == userB) ||
                    (it.userAId == userB && it.userBId == userA)
            }
        )
        assertEquals(
            1,
            activeEngagementLockRepository.countByUserIdAndEngagementType(userA, EngagementType.MATCH)
        )
        assertEquals(
            1,
            activeEngagementLockRepository.countByUserIdAndEngagementType(userB, EngagementType.MATCH)
        )
    }

    private fun assertMatchedUsersAreDistinct(expectedUserCount: Int? = null) {
        val matchedUserIds =
            matchRepository
                .findAll()
                .flatMap { listOf(it.userAId, it.userBId) }

        expectedUserCount?.let {
            assertEquals(it, matchedUserIds.size)
        }
        assertEquals(matchedUserIds.size, matchedUserIds.toSet().size)
    }

    private fun processConcurrently(workerCount: Int): List<MatchmakingProcessResult> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val futures = (1..workerCount).map {
                executor.submit(
                    Callable {
                        start.await()
                        matchmakingProcessorService.process(maxPairsPerRun = 1)
                    }
                )
            }

            start.countDown()

            return futures.map {
                it.get(15, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
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

            return futures.map {
                it.get(15, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun createActiveProfile(
        email: String,
        displayName: String,
        gender: Gender,
        lookingForGenders: Set<Gender>,
        maxDistanceKm: Int = 50
    ): UUID {
        val user = userService.createUser(email)
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = displayName,
            birthDate = LocalDate.of(1995, 1, 1),
            gender = gender,
            lookingForGenders = lookingForGenders,
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            bio = "Postgres concurrency integration test profile",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = maxDistanceKm
        )

        repeat(4) { index ->
            profilePhotoRepository.save(
                ProfilePhoto(
                    profileId = profile.id,
                    storageProvider = PhotoStorageProvider.S3,
                    storageBucket = "reals-media-test",
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

    private fun enqueueForMatchmaking(
        userId: UUID,
        latitude: Double = BUENOS_AIRES_LATITUDE,
        longitude: Double = BUENOS_AIRES_LONGITUDE
    ) {
        matchmakingService.enqueue(
            userId = userId,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = 50
        )
    }

    private class KPostgreSQLContainer(imageName: String) :
        PostgreSQLContainer<KPostgreSQLContainer>(imageName)

    companion object {

        private const val BUENOS_AIRES_LATITUDE = -34.6037
        private const val BUENOS_AIRES_LONGITUDE = -58.3816

        @Container
        @JvmStatic
        private val postgres = KPostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun registerPostgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }
}
