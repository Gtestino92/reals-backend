package com.reals.backend.integration.service

import com.reals.backend.domain.Gender
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.context.TestPropertySource
import java.util.UUID

@TestPropertySource(
    properties = [
        "matchmaking.allow-active-pair-duplicates=true",
        "matchmaking.exclude-previous-pairing=false",
        "engagement.max-active-matches=1"
    ]
)
class MatchmakingActivePairDuplicatesCapacityIntegrationTest : BaseIT() {

    @Test
    fun `active duplicate mode does not bypass active match capacity`() {
        val userA = createActiveProfile(
            email = "active-duplicate-capacity-a-${UUID.randomUUID()}@example.com",
            displayName = "Active Duplicate Capacity A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "active-duplicate-capacity-b-${UUID.randomUUID()}@example.com",
            displayName = "Active Duplicate Capacity B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        matchService.createMatch(userA, userB)

        assertThrows<IllegalStateException> {
            matchService.createMatch(userA, userB)
        }
    }
}
