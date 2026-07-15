package com.reals.backend.integration.service

import com.reals.backend.domain.MatchmakingPartnerCandidate
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.matching.MatchmakingCandidateRepository
import com.reals.backend.repository.matching.MatchmakingPairExclusionPolicy
import com.reals.backend.service.matching.MatchmakingProcessorService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MatchmakingLargeQueuePostgresIntegrationTest {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var namedJdbcTemplate: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var matchmakingCandidateRepository: MatchmakingCandidateRepository

    @Autowired
    private lateinit var matchmakingProcessorService: MatchmakingProcessorService

    @Autowired
    private lateinit var matchRepository: MatchRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute(
            """
            TRUNCATE TABLE
                active_engagement_locks,
                chats,
                matches,
                matchmaking_queue,
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
    fun `large persisted queue discovers bounded eligible partner window before processing`() {
        val baseTime = OffsetDateTime.parse("2026-07-14T12:00:00Z")
        val anchor =
            insertQueuedUser(
                label = "large-anchor",
                gender = "FEMALE",
                lookingForGender = "MALE",
                enteredAt = baseTime,
                latitude = BUENOS_AIRES_LATITUDE,
                longitude = BUENOS_AIRES_LONGITUDE
            )
        val ineligibleUsers =
            buildList {
                repeat(20) { index ->
                    add(
                        insertQueuedUser(
                            label = "large-incompatible-$index",
                            gender = "FEMALE",
                            lookingForGender = "MALE",
                            enteredAt = baseTime.plusSeconds(index + 1L),
                            latitude = BUENOS_AIRES_LATITUDE,
                            longitude = BUENOS_AIRES_LONGITUDE
                        )
                    )
                }
                repeat(20) { index ->
                    add(
                        insertQueuedUser(
                            label = "large-far-$index",
                            gender = "MALE",
                            lookingForGender = "FEMALE",
                            enteredAt = baseTime.plusSeconds(21L + index),
                            latitude = 0.0,
                            longitude = 0.0
                        )
                    )
                }
                repeat(20) { index ->
                    val user =
                        insertQueuedUser(
                            label = "large-penalized-$index",
                            gender = "MALE",
                            lookingForGender = "FEMALE",
                            enteredAt = baseTime.plusSeconds(41L + index),
                            latitude = BUENOS_AIRES_LATITUDE,
                            longitude = BUENOS_AIRES_LONGITUDE
                        )
                    insertActivePenalty(user.userId)
                    add(user)
                }
                repeat(19) { index ->
                    val user =
                        insertQueuedUser(
                            label = "large-blocked-$index",
                            gender = "MALE",
                            lookingForGender = "FEMALE",
                            enteredAt = baseTime.plusSeconds(61L + index),
                            latitude = BUENOS_AIRES_LATITUDE,
                            longitude = BUENOS_AIRES_LONGITUDE
                        )
                    insertBlock(blockerUserId = user.userId, blockedUserId = anchor.userId)
                    add(user)
                }
            }
        val eligiblePartners =
            (0..<120).map { index ->
                insertQueuedUser(
                    label = "large-compatible-$index",
                    gender = "MALE",
                    lookingForGender = "FEMALE",
                    enteredAt = baseTime.plusSeconds(80L + index),
                    latitude = BUENOS_AIRES_LATITUDE,
                    longitude = BUENOS_AIRES_LONGITUDE
                )
            }
        val allFixtureUsers = listOf(anchor) + ineligibleUsers + eligiblePartners
        assertEquals(200, queuedCount(allFixtureUsers.map { it.userId }))

        val discovered =
            TransactionTemplate(transactionManager).execute<List<MatchmakingPartnerCandidate>> {
                val claimedAnchor =
                    matchmakingCandidateRepository.claimNextEligibleAnchorForUpdate(
                        today = LocalDate.of(2026, 7, 14),
                        exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
                        previousPairingCutoff = null,
                        firstChatExpirationCutoff = null
                    ) ?: error("Expected anchor")
                assertEquals(anchor.userId, claimedAnchor.userId)

                matchmakingCandidateRepository.findEligiblePartnerCandidates(
                    anchorQueueEntryId = claimedAnchor.queueEntryId,
                    limit = 50,
                    today = LocalDate.of(2026, 7, 14),
                    exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
                    previousPairingCutoff = null,
                    firstChatExpirationCutoff = null
                )
            }

        assertEquals(50, discovered.size)
        assertEquals(
            eligiblePartners.take(50).map { it.userId },
            discovered.map { it.pair.userBId }
        )
        assertEquals(
            eligiblePartners.take(50).map { it.enteredAt },
            discovered.map { it.partnerEnteredAt }
        )
        assertEquals(
            emptySet<UUID>(),
            discovered.map { it.pair.userBId }.toSet().intersect(ineligibleUsers.map { it.userId }.toSet())
        )

        val result = matchmakingProcessorService.process(maxPairsPerRun = 1)

        assertEquals(1, result.matchesCreated)
        assertEquals(0, result.failedPairs)
        assertEquals(198, queuedCount(allFixtureUsers.map { it.userId }))
        assertFalse(isQueued(anchor.userId))
        assertFalse(isQueued(eligiblePartners.first().userId))
        val selectedUserIds = setOf(anchor.userId, eligiblePartners.first().userId)
        assertEquals(
            198,
            allFixtureUsers.count { it.userId !in selectedUserIds && isQueued(it.userId) }
        )

        val fixtureMatches =
            matchRepository.findAll()
                .filter { match ->
                    allFixtureUsers.any { it.userId == match.userAId || it.userId == match.userBId }
                }
        assertEquals(1, fixtureMatches.size)
        assertEquals(
            setOf(anchor.userId, eligiblePartners.first().userId),
            setOf(fixtureMatches.single().userAId, fixtureMatches.single().userBId)
        )
    }

    @Test
    fun `large queue processor run creates multiple disjoint matches sequentially`() {
        val baseTime = OffsetDateTime.parse("2026-07-14T13:00:00Z")
        val users =
            buildList {
                repeat(50) { index ->
                    add(
                        insertQueuedUser(
                            label = "progress-female-$index",
                            gender = "FEMALE",
                            lookingForGender = "MALE",
                            enteredAt = baseTime.plusSeconds(index.toLong()),
                            latitude = BUENOS_AIRES_LATITUDE,
                            longitude = BUENOS_AIRES_LONGITUDE
                        )
                    )
                }
                repeat(50) { index ->
                    add(
                        insertQueuedUser(
                            label = "progress-male-$index",
                            gender = "MALE",
                            lookingForGender = "FEMALE",
                            enteredAt = baseTime.plusSeconds(50L + index),
                            latitude = BUENOS_AIRES_LATITUDE,
                            longitude = BUENOS_AIRES_LONGITUDE
                        )
                    )
                }
            }
        val fixtureUserIds = users.map { it.userId }.toSet()

        val result = matchmakingProcessorService.process(maxPairsPerRun = 20)

        assertEquals(20, result.matchesCreated)
        assertEquals(0, result.failedPairs)
        val fixtureMatches =
            matchRepository.findAll()
                .filter { it.userAId in fixtureUserIds || it.userBId in fixtureUserIds }
        assertEquals(20, fixtureMatches.size)
        val matchedUserIds = fixtureMatches.flatMap { listOf(it.userAId, it.userBId) }
        assertEquals(40, matchedUserIds.size)
        assertEquals(40, matchedUserIds.toSet().size)
        assertEquals(60, queuedCount(fixtureUserIds))
    }

    private fun insertQueuedUser(
        label: String,
        gender: String,
        lookingForGender: String,
        enteredAt: OffsetDateTime,
        latitude: Double,
        longitude: Double
    ): QueuedUser {
        val userId = UUID.nameUUIDFromBytes("$label-user".toByteArray())
        val profileId = UUID.nameUUIDFromBytes("$label-profile".toByteArray())
        val queueEntryId = UUID.nameUUIDFromBytes("$label-queue".toByteArray())
        jdbcTemplate.update(
            """
            INSERT INTO users (id, email, status, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            userId,
            "$label@example.com",
            enteredAt,
            enteredAt
        )
        jdbcTemplate.update(
            """
            INSERT INTO profiles (
                id,
                user_id,
                display_name,
                birth_date,
                authenticity_verified,
                authenticity_verification_status,
                gender,
                intention,
                city,
                country_code,
                bio,
                preferred_min_age,
                preferred_max_age,
                max_distance_km,
                status,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, false, 'NOT_STARTED', ?, 'DATE', 'Buenos Aires', 'AR', ?, 18, 99, 50, 'ACTIVE', ?, ?)
            """.trimIndent(),
            profileId,
            userId,
            label,
            LocalDate.of(1995, 1, 1),
            gender,
            "Large queue test profile",
            enteredAt,
            enteredAt
        )
        jdbcTemplate.update(
            """
            INSERT INTO profile_looking_for_genders (profile_id, gender)
            VALUES (?, ?)
            """.trimIndent(),
            profileId,
            lookingForGender
        )
        jdbcTemplate.update(
            """
            INSERT INTO matchmaking_queue (id, user_id, status, entered_at, latitude, longitude, accuracy_meters)
            VALUES (?, ?, 'WAITING', ?, ?, ?, 50)
            """.trimIndent(),
            queueEntryId,
            userId,
            enteredAt,
            latitude,
            longitude
        )

        return QueuedUser(
            userId = userId,
            queueEntryId = queueEntryId,
            enteredAt = enteredAt
        )
    }

    private fun insertActivePenalty(userId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO penalties (id, user_id, reason, type, expires_at, active, created_at)
            VALUES (?, ?, 'Large queue active penalty', 'TEMPORARY_BAN', ?, true, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            userId,
            OffsetDateTime.now().plusDays(1),
            OffsetDateTime.now()
        )
    }

    private fun insertBlock(
        blockerUserId: UUID,
        blockedUserId: UUID
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id, source, created_at)
            VALUES (?, ?, ?, 'MANUAL', ?)
            """.trimIndent(),
            UUID.randomUUID(),
            blockerUserId,
            blockedUserId,
            OffsetDateTime.now()
        )
    }

    private fun queuedCount(userIds: Collection<UUID>): Long =
        namedJdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM matchmaking_queue
            WHERE user_id IN (:userIds)
            """.trimIndent(),
            mapOf("userIds" to userIds),
            Long::class.java
        ) ?: 0

    private fun isQueued(userId: UUID): Boolean =
        queuedCount(listOf(userId)) == 1L

    private data class QueuedUser(
        val userId: UUID,
        @Suppress("unused")
        val queueEntryId: UUID,
        val enteredAt: OffsetDateTime
    )

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
            registry.add("matchmaking.candidate-pair-limit") { "50" }
            registry.add("matchmaking.ranking.mode") { "LEGACY_EARLY_ACCEPT" }
            registry.add("matchmaking.exclude-previous-pairing") { "false" }
        }
    }
}
