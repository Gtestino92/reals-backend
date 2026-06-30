package com.reals.backend.integration.service

import com.reals.backend.domain.Gender
import com.reals.backend.domain.LookingForGender
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomeStatusServiceIntegrationTest : BaseIT() {

    @Test
    fun `getOrCreateStatus lazily creates default row`() {
        val user = userService.createUser("home-status-default-${UUID.randomUUID()}@example.com")

        assertFalse(homeStatusRepository.existsById(user.id))

        val status = homeStatusService.getOrCreateStatus(user.id)

        assertEquals(user.id, status.userId)
        assertEquals(0, status.version)
        assertFalse(status.dirty)
        assertNotNull(homeStatusRepository.findById(user.id).orElse(null))
    }

    @Test
    fun `bump increments version monotonically and marks dirty`() {
        val user = userService.createUser("home-status-bump-${UUID.randomUUID()}@example.com")

        val first = homeStatusService.bump(
            userId = user.id,
            reason = "test_first_bump"
        )
        val second = homeStatusService.bump(
            userId = user.id,
            reason = "test_second_bump"
        )

        assertEquals(1, first.version)
        assertTrue(first.dirty)
        assertEquals(2, second.version)
        assertTrue(second.dirty)
    }

    @Test
    fun `bumpBoth affects both users`() {
        val userA = userService.createUser("home-status-both-a-${UUID.randomUUID()}@example.com")
        val userB = userService.createUser("home-status-both-b-${UUID.randomUUID()}@example.com")

        homeStatusService.bumpBoth(
            userAId = userA.id,
            userBId = userB.id,
            reason = "test_bump_both"
        )

        assertEquals(1, homeStatusService.getOrCreateStatus(userA.id).version)
        assertEquals(1, homeStatusService.getOrCreateStatus(userB.id).version)
    }

    @Test
    fun `matchmaking enqueue bumps home status version`() {
        val userId = createActiveProfile(
            email = "home-status-enqueue-${UUID.randomUUID()}@example.com",
            displayName = "Home Status Enqueue",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val before = homeStatusService.getOrCreateStatus(userId).version

        enqueueForMatchmaking(userId)

        val after = homeStatusService.getOrCreateStatus(userId)
        assertEquals(before + 1, after.version)
        assertTrue(after.dirty)
    }

    @Test
    fun `markCleanIfVersionStill clears dirty when version still matches`() {
        val user = userService.createUser("home-status-clean-match-${UUID.randomUUID()}@example.com")
        val before = homeStatusService.bump(
            userId = user.id,
            reason = "test_clean_match"
        )

        val cleaned = homeStatusService.markCleanIfVersionStill(
            userId = user.id,
            expectedVersion = before.version
        )
        val after = homeStatusService.getOrCreateStatus(user.id)

        assertTrue(cleaned)
        assertEquals(before.version, after.version)
        assertFalse(after.dirty)
    }

    @Test
    fun `markCleanIfVersionStill does not clear newer dirty version`() {
        val user = userService.createUser("home-status-clean-stale-${UUID.randomUUID()}@example.com")
        val before = homeStatusService.bump(
            userId = user.id,
            reason = "test_clean_stale_first"
        )
        homeStatusService.bump(
            userId = user.id,
            reason = "test_clean_stale_second"
        )

        val cleaned = homeStatusService.markCleanIfVersionStill(
            userId = user.id,
            expectedVersion = before.version
        )
        val after = homeStatusService.getOrCreateStatus(user.id)

        assertFalse(cleaned)
        assertEquals(before.version + 1, after.version)
        assertTrue(after.dirty)
    }
}
