package com.reals.backend.integration.controller

import com.reals.backend.domain.NotificationPreferenceCategory
import com.reals.backend.integration.ControllerIT
import com.reals.backend.repository.UserNotificationPreferenceRepository
import com.reals.backend.service.NotificationPreferenceService
import com.reals.backend.service.NotificationPreferenceSettings
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class NotificationPreferenceControllerIntegrationTest : ControllerIT() {

    @Autowired
    private lateinit var notificationPreferenceService: NotificationPreferenceService

    @Autowired
    private lateinit var notificationPreferenceRepository: UserNotificationPreferenceRepository

    @Test
    fun `new user with no preference rows resolves all configurable defaults enabled`() {
        val user = userService.createUser("notification-defaults-${UUID.randomUUID()}@example.com")

        val preferences = notificationPreferenceService.preferencesFor(user.id)

        assertEquals(
            NotificationPreferenceSettings(
                activityEnabled = true,
                remindersEnabled = true,
                availabilityEnabled = true
            ),
            preferences
        )
        assertEquals(0, notificationPreferenceRepository.findByUserId(user.id).size)
    }

    @Test
    fun `get notification preferences returns default enabled state without rows`() {
        val user = userService.createUser("notification-get-defaults-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/me/notification-preferences")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activityEnabled", equalTo(true)))
            .andExpect(jsonPath("$.remindersEnabled", equalTo(true)))
            .andExpect(jsonPath("$.availabilityEnabled", equalTo(true)))
            .andExpect(jsonPath("$.systemEnabled").doesNotExist())

        assertEquals(0, notificationPreferenceRepository.findByUserId(user.id).size)
    }

    @Test
    fun `put notification preferences persists complete state and repeated puts are idempotent`() {
        val user = userService.createUser("notification-put-${UUID.randomUUID()}@example.com")
        val body =
            """
            {
              "activityEnabled": true,
              "remindersEnabled": false,
              "availabilityEnabled": true
            }
            """.trimIndent()

        repeat(2) {
            mockMvc.perform(
                put("/api/me/notification-preferences")
                    .with(authenticatedAs(user.id))
                    .contentType(jsonContentType)
                    .content(body)
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.activityEnabled", equalTo(true)))
                .andExpect(jsonPath("$.remindersEnabled", equalTo(false)))
                .andExpect(jsonPath("$.availabilityEnabled", equalTo(true)))
                .andExpect(jsonPath("$.systemEnabled").doesNotExist())
        }

        assertEquals(1, notificationPreferenceRepository.countByUserIdAndCategory(user.id, NotificationPreferenceCategory.ACTIVITY))
        assertEquals(1, notificationPreferenceRepository.countByUserIdAndCategory(user.id, NotificationPreferenceCategory.REMINDERS))
        assertEquals(1, notificationPreferenceRepository.countByUserIdAndCategory(user.id, NotificationPreferenceCategory.AVAILABILITY))
        assertEquals(0, notificationPreferenceRepository.countByUserIdAndCategory(user.id, NotificationPreferenceCategory.SYSTEM))

        mockMvc.perform(
            get("/api/me/notification-preferences")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activityEnabled", equalTo(true)))
            .andExpect(jsonPath("$.remindersEnabled", equalTo(false)))
            .andExpect(jsonPath("$.availabilityEnabled", equalTo(true)))
    }

    @Test
    fun `updating persisted category changes value without creating duplicates`() {
        val user = userService.createUser("notification-update-${UUID.randomUUID()}@example.com")

        notificationPreferenceService.updatePreferences(
            userId = user.id,
            input = NotificationPreferenceSettings(
                activityEnabled = true,
                remindersEnabled = false,
                availabilityEnabled = true
            )
        )
        notificationPreferenceService.updatePreferences(
            userId = user.id,
            input = NotificationPreferenceSettings(
                activityEnabled = false,
                remindersEnabled = true,
                availabilityEnabled = false
            )
        )

        val preferences = notificationPreferenceService.preferencesFor(user.id)
        assertEquals(false, preferences.activityEnabled)
        assertEquals(true, preferences.remindersEnabled)
        assertEquals(false, preferences.availabilityEnabled)
        assertEquals(3, notificationPreferenceRepository.findByUserId(user.id).size)
    }

    @Test
    fun `preferences are account settings independent of profile existence`() {
        val noProfileUser = userService.createUser("notification-no-profile-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            put("/api/me/notification-preferences")
                .with(authenticatedAs(noProfileUser.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "activityEnabled": false,
                      "remindersEnabled": false,
                      "availabilityEnabled": true
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activityEnabled", equalTo(false)))
            .andExpect(jsonPath("$.remindersEnabled", equalTo(false)))
            .andExpect(jsonPath("$.availabilityEnabled", equalTo(true)))

        val draftProfileUser = userService.createUser("notification-draft-profile-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = draftProfileUser.id,
            displayName = "Draft Preferences",
            birthDate = java.time.LocalDate.of(1995, 1, 1),
            gender = com.reals.backend.domain.Gender.FEMALE,
            lookingForGenders = setOf(com.reals.backend.domain.Gender.MALE),
            intention = com.reals.backend.domain.Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            bio = "Draft profile",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            get("/api/me/notification-preferences")
                .with(authenticatedAs(draftProfileUser.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activityEnabled", equalTo(true)))
            .andExpect(jsonPath("$.remindersEnabled", equalTo(true)))
            .andExpect(jsonPath("$.availabilityEnabled", equalTo(true)))
    }

    @Test
    fun `system category is never persisted as configurable preference`() {
        val user = userService.createUser("notification-system-${UUID.randomUUID()}@example.com")

        notificationPreferenceService.updatePreferences(
            userId = user.id,
            input = NotificationPreferenceSettings(
                activityEnabled = false,
                remindersEnabled = false,
                availabilityEnabled = false
            )
        )

        val categories = notificationPreferenceRepository.findByUserId(user.id).map { it.category }.toSet()
        assertFalse(NotificationPreferenceCategory.SYSTEM in categories)
    }
}
