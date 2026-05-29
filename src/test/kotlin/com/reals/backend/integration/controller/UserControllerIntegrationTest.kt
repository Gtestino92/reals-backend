package com.reals.backend.integration.controller

import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class UserControllerIntegrationTest : ControllerIT() {

    @Test
    fun `invalid email returns bad request`() {
        mockMvc.perform(
            post("/api/users")
                .with(authenticatedAs(UUID.randomUUID()))
                .contentType(jsonContentType)
                .content("""{"email":"not-an-email"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }
}
