package com.reals.backend.integration.service

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
import com.reals.backend.service.matching.MatchmakingProcessorService
import com.reals.backend.service.matching.MatchmakingService
import com.reals.backend.service.ProfileService
import com.reals.backend.service.UserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
    private lateinit var matchRepository: MatchRepository

    @Autowired
    private lateinit var profilePhotoRepository: ProfilePhotoRepository

    @Autowired
    private lateinit var matchmakingQueueRepository: MatchmakingQueueRepository

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
        assertTrue(
            concurrentlyCreated in 1..2,
            "Expected at least one concurrent processor to create a match without creating more than the available pairs"
        )
        assertMatchedUsersAreDistinct()

        matchmakingProcessorService.process(maxPairsPerRun = 2)

        assertMatchedUsersAreDistinct(expectedUserCount = 4)
        assertEquals(2, matchRepository.count())
        assertEquals(0L, matchmakingQueueRepository.count())
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

    private fun createActiveProfile(
        email: String,
        displayName: String,
        gender: Gender,
        lookingForGenders: Set<Gender>
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
            maxDistanceKm = 50
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

    private fun enqueueForMatchmaking(userId: UUID) {
        matchmakingService.enqueue(
            userId = userId,
            latitude = BUENOS_AIRES_LATITUDE,
            longitude = BUENOS_AIRES_LONGITUDE,
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
