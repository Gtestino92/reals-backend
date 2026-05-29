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
            "bio" to "Created through MockMvc"
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
            .andExpect(jsonPath("$.status", equalTo("DRAFT")))
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
            "country" to "AR"
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }

    @Test
    fun `invalid photo position returns bad request before service lookup`() {
        val user = userService.createUser("photo-position-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            put("/api/me/profile/photos/0")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content("""{"url":"https://example.com/photo.jpg"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }
}
