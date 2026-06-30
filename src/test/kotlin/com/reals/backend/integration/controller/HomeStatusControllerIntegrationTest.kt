package com.reals.backend.integration.controller

import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.MeHomeService
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class HomeStatusControllerIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var meHomeService: MeHomeService

    @Test
    fun `home status endpoint creates default row without calling full home aggregation`() {
        val user = userService.createUser("home-status-controller-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/me/home/status")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version", equalTo(0)))
            .andExpect(jsonPath("$.dirty", equalTo(false)))
            .andExpect(jsonPath("$.serverTime").exists())

        verifyNoInteractions(meHomeService)
    }
}
