package com.reals.backend.integration.postgres

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.VisualReview
import com.reals.backend.repository.MatchmakingAvailabilityNotificationEpisodeRepository
import com.reals.backend.service.matching.VisualAdvancementCapService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@TestPropertySource(
    properties = [
        "matchmaking.visual-advancement.max-per-window=2",
        "matchmaking.visual-advancement.window-hours=24"
    ]
)
class VisualReviewRepositoryPostgresIT : PostgresITBase() {

    @Autowired
    private lateinit var visualAdvancementCapService: VisualAdvancementCapService

    @Autowired
    private lateinit var availabilityEpisodeRepository: MatchmakingAvailabilityNotificationEpisodeRepository

    @Test
    fun `native retry threshold query materializes timestamptz as instant and preserves cap ordering`() {
        val cutoff = OffsetDateTime.parse("2026-08-21T00:00:00Z")
        val userId = activeFemale("pg-visual-repository")
        val partnerId = activeMale("pg-visual-repository")
        val tieCreatedAt = OffsetDateTime.parse("2026-08-21T02:30:00Z")
        val lowerTieId = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val higherTieId = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val nonUtcCreatedAt = OffsetDateTime.parse("2026-08-21T01:00:00-03:00")
        val newestCreatedAt = OffsetDateTime.parse("2026-08-21T05:00:00Z")

        saveVisualAdvancement(
            userId = userId,
            partnerId = partnerId,
            reviewId = UUID.fromString("00000000-0000-0000-0000-000000000999"),
            createdAt = cutoff
        )
        saveVisualAdvancement(
            userId = userId,
            partnerId = partnerId,
            reviewId = lowerTieId,
            createdAt = tieCreatedAt
        )
        saveVisualAdvancement(
            userId = userId,
            partnerId = partnerId,
            reviewId = higherTieId,
            createdAt = tieCreatedAt
        )
        saveVisualAdvancement(
            userId = userId,
            partnerId = partnerId,
            reviewId = UUID.fromString("00000000-0000-0000-0000-000000000201"),
            createdAt = nonUtcCreatedAt
        )
        saveVisualAdvancement(
            userId = userId,
            partnerId = partnerId,
            reviewId = UUID.fromString("00000000-0000-0000-0000-000000000301"),
            createdAt = newestCreatedAt
        )

        val activeCount = visualReviewRepository.countAdvancementsForUserCreatedAfter(
            userId = userId,
            cutoff = cutoff
        )
        val offsetZero = visualReviewRepository.findRetryThresholdAdvancementCreatedAfter(
            userId = userId,
            cutoff = cutoff,
            offset = 0
        )
        val offsetOne = visualReviewRepository.findRetryThresholdAdvancementCreatedAfter(
            userId = userId,
            cutoff = cutoff,
            offset = 1
        )
        val offsetTwo = visualReviewRepository.findRetryThresholdAdvancementCreatedAfter(
            userId = userId,
            cutoff = cutoff,
            offset = 2
        )
        val offsetThree = visualReviewRepository.findRetryThresholdAdvancementCreatedAfter(
            userId = userId,
            cutoff = cutoff,
            offset = 3
        )
        val offsetPastActiveRows = visualReviewRepository.findRetryThresholdAdvancementCreatedAfter(
            userId = userId,
            cutoff = cutoff,
            offset = 4
        )
        val tieOrderedIds = orderedActiveReviewIds(
            userId = userId,
            cutoff = cutoff
        ).filter { it == lowerTieId || it == higherTieId }

        assertEquals(4L, activeCount)
        assertEquals(Instant.parse("2026-08-21T05:00:00Z"), offsetZero)
        assertEquals(Instant.parse("2026-08-21T04:00:00Z"), offsetOne)
        assertEquals(nonUtcCreatedAt.toInstant(), offsetOne)
        assertEquals(Instant.parse("2026-08-21T02:30:00Z"), offsetTwo)
        assertEquals(Instant.parse("2026-08-21T02:30:00Z"), offsetThree)
        assertNull(offsetPastActiveRows)
        assertEquals(listOf(higherTieId, lowerTieId), tieOrderedIds)
    }

    @Test
    fun `cap service converts postgres instant threshold to utc offset timestamp`() {
        val now = OffsetDateTime.parse("2026-08-21T06:00:00Z")
        val userId = activeFemale("pg-visual-service")
        val partnerId = activeMale("pg-visual-service")
        val thresholdCreatedAt = OffsetDateTime.parse("2026-08-21T01:00:00-03:00")
        saveVisualAdvancement(
            userId = userId,
            partnerId = partnerId,
            reviewId = UUID.fromString("00000000-0000-0000-0000-000000000401"),
            createdAt = thresholdCreatedAt
        )
        saveVisualAdvancement(
            userId = userId,
            partnerId = partnerId,
            reviewId = UUID.fromString("00000000-0000-0000-0000-000000000402"),
            createdAt = OffsetDateTime.parse("2026-08-21T05:00:00Z")
        )

        assertEquals(
            2L,
            visualReviewRepository.countAdvancementsForUserCreatedAfter(
                userId = userId,
                cutoff = now.minusHours(24)
            )
        )

        val status = visualAdvancementCapService.statusFor(
            userId = userId,
            now = now
        )

        assertTrue(status.blocked)
        assertEquals(OffsetDateTime.parse("2026-08-22T04:00:00Z"), status.nextAvailableAt)
    }

    @Test
    fun `availability discovery query counts user A and user B advancements without duplicates`() {
        val now = OffsetDateTime.parse("2026-08-22T12:00:00Z")
        val cutoff = now.minusHours(24)
        val onlyUserA = activeFemale("pg-availability-a")
        val onlyUserB = activeMale("pg-availability-b")
        val bothSides = activeFemale("pg-availability-both")
        val partnerA = activeMale("pg-availability-partner-a")
        val partnerB = activeFemale("pg-availability-partner-b")
        val partnerC = activeMale("pg-availability-partner-c")
        val belowLimit = activeFemale("pg-availability-below")

        saveVisualAdvancement(onlyUserA, partnerA, UUID.randomUUID(), cutoff.plusHours(1))
        saveVisualAdvancement(onlyUserA, partnerC, UUID.randomUUID(), cutoff.plusHours(2))
        saveVisualAdvancement(partnerB, onlyUserB, UUID.randomUUID(), cutoff.plusHours(3))
        saveVisualAdvancement(partnerA, onlyUserB, UUID.randomUUID(), cutoff.plusHours(4))
        saveVisualAdvancement(bothSides, partnerA, UUID.randomUUID(), cutoff.plusHours(5))
        saveVisualAdvancement(partnerB, bothSides, UUID.randomUUID(), cutoff.plusHours(6))
        saveVisualAdvancement(belowLimit, partnerC, UUID.randomUUID(), cutoff.plusHours(7))

        val discovered =
            availabilityEpisodeRepository.findUsersAtOrOverVisualAdvancementCap(
                cutoff = cutoff,
                limit = 2,
                pageable = org.springframework.data.domain.PageRequest.of(0, 20)
            )

        assertEquals(
            setOf(onlyUserA, onlyUserB, bothSides),
            discovered.map { UUID.fromString(it) }.toSet()
        )
        assertEquals(discovered.toSet().size, discovered.size)
    }

    private fun saveVisualAdvancement(
        userId: UUID,
        partnerId: UUID,
        reviewId: UUID,
        createdAt: OffsetDateTime
    ): VisualReview {
        val match = matchRepository.saveAndFlush(
            Match(
                userAId = userId,
                userBId = partnerId,
                state = MatchState.VISUAL_PHASE,
                createdAt = createdAt.minusMinutes(15),
                updatedAt = createdAt
            )
        )
        return visualReviewRepository.saveAndFlush(
            VisualReview(
                id = reviewId,
                matchId = match.id,
                expiresAt = createdAt.plusHours(24),
                availableAt = createdAt,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        )
    }

    private fun orderedActiveReviewIds(
        userId: UUID,
        cutoff: OffsetDateTime
    ): List<UUID> =
        jdbcTemplate.queryForList(
            """
            select v.id
            from visual_reviews v
            join matches m
              on m.id = v.match_id
            where v.created_at > ?
              and (
                m.user_a_id = ?
                or m.user_b_id = ?
              )
            order by v.created_at desc, v.id desc
            """.trimIndent(),
            UUID::class.java,
            cutoff,
            userId,
            userId
        ).filterNotNull()

    private fun activeFemale(prefix: String): UUID =
        createActiveProfile(
            email = "$prefix-${UUID.randomUUID()}@example.com",
            displayName = "$prefix user",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

    private fun activeMale(prefix: String): UUID =
        createActiveProfile(
            email = "$prefix-${UUID.randomUUID()}@example.com",
            displayName = "$prefix user",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
}
