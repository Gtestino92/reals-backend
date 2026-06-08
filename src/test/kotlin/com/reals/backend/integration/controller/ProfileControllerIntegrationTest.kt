package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

class ProfileControllerIntegrationTest : ControllerIT() {

    @Test
    fun `get me resolves authenticated user id`() {
        val user = userService.createUser("me-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.email", equalTo(user.email)))
    }

    @Test
    fun `create profile uses authenticated user id`() {
        val user = userService.createUser("profile-${UUID.randomUUID()}@example.com")
        val body = mapOf(
            "displayName" to "Controller Profile",
            "birthDate" to LocalDate.of(1995, 1, 1).toString(),
            "gender" to Gender.FEMALE.name,
            "lookingForGender" to LookingForGender.MEN.name,
            "intention" to Intention.DATE.name,
            "city" to "Buenos Aires",
            "country" to "AR",
            "bio" to "Created through MockMvc",
            "preferredMinAge" to 30,
            "preferredMaxAge" to 40,
            "maxDistanceKm" to 75
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.userId", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.displayName", equalTo("Controller Profile")))
            .andExpect(jsonPath("$.preferredMinAge", equalTo(30)))
            .andExpect(jsonPath("$.preferredMaxAge", equalTo(40)))
            .andExpect(jsonPath("$.maxDistanceKm", equalTo(75)))
            .andExpect(jsonPath("$.status", equalTo("DRAFT")))
    }

    @Test
    fun `update match filters replaces required dynamic filters`() {
        val user = userService.createUser("filters-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Filter Profile",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            preferredMinAge = 25,
            preferredMaxAge = 35,
            maxDistanceKm = 100
        )

        mockMvc.perform(
            put("/api/me/profile/match-filters")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "preferredMinAge": 30,
                      "preferredMaxAge": 38,
                      "maxDistanceKm": 25
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.preferredMinAge", equalTo(30)))
            .andExpect(jsonPath("$.preferredMaxAge", equalTo(38)))
            .andExpect(jsonPath("$.maxDistanceKm", equalTo(25)))
    }

    @Test
    fun `underage profile returns bad request`() {
        val user = userService.createUser("underage-${UUID.randomUUID()}@example.com")
        val body = mapOf(
            "displayName" to "Young Profile",
            "birthDate" to LocalDate.now().minusYears(17).toString(),
            "gender" to Gender.FEMALE.name,
            "lookingForGender" to LookingForGender.MEN.name,
            "intention" to Intention.DATE.name,
            "city" to "Buenos Aires",
            "country" to "AR",
            "preferredMinAge" to 18,
            "preferredMaxAge" to 99,
            "maxDistanceKm" to 50
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_BIRTH_DATE")))
    }

    @Test
    fun `create duplicate profile returns stable error code`() {
        val user = userService.createUser("duplicate-profile-${UUID.randomUUID()}@example.com")
        val body = mapOf(
            "displayName" to "Duplicate Profile",
            "birthDate" to LocalDate.of(1995, 1, 1).toString(),
            "gender" to Gender.FEMALE.name,
            "lookingForGender" to LookingForGender.MEN.name,
            "intention" to Intention.DATE.name,
            "city" to "Buenos Aires",
            "country" to "AR",
            "preferredMinAge" to 18,
            "preferredMaxAge" to 99,
            "maxDistanceKm" to 50
        )

        profileService.createProfile(
            userId = user.id,
            displayName = "Existing Profile",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_ALREADY_EXISTS")))
    }

    @Test
    fun `activate profile without required photos returns stable error code`() {
        val user = userService.createUser("activate-missing-photos-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Missing Photos",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            post("/api/me/profile/activation")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_PHOTOS_REQUIRED")))
    }

    @Test
    fun `update match filters rejects inverted age range with stable error code`() {
        val user = userService.createUser("filters-invalid-range-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Invalid Filters",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            put("/api/me/profile/match-filters")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "preferredMinAge": 40,
                      "preferredMaxAge": 30,
                      "maxDistanceKm": 50
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_MATCH_FILTERS")))
    }

    @Test
    fun `invalid photo position returns bad request before service lookup`() {
        val user = userService.createUser("photo-position-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            put("/api/me/profile/photos/position/0")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content("""{"url":"https://example.com/photo.jpg"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }
}
