package com.reals.backend.integration.controller

import com.reals.backend.domain.ActiveEngagementLock
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class AdminDiagnosticsControllerIntegrationTest : ControllerIT() {

    @Test
    fun `admin can read aggregate matchmaking diagnostics`() {
        val admin = userService.createUser("admin-diagnostics-${UUID.randomUUID()}@example.com")
        val queuedUser = createActiveProfile(
            email = "diagnostics-queued-${UUID.randomUUID()}@example.com",
            displayName = "Diagnostics Queued",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        enqueueForMatchmaking(queuedUser)

        lockRepository.save(
            ActiveEngagementLock(
                userId = UUID.randomUUID(),
                engagementId = UUID.randomUUID(),
                engagementType = EngagementType.MATCH
            )
        )
        lockRepository.save(
            ActiveEngagementLock(
                userId = UUID.randomUUID(),
                engagementId = UUID.randomUUID(),
                engagementType = EngagementType.CONNECTION
            )
        )

        mockMvc.perform(
            get("/api/admin/diagnostics/matchmaking")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.queueWaitingCount", equalTo(1)))
            .andExpect(jsonPath("$.queueTotalCount", equalTo(1)))
            .andExpect(jsonPath("$.activeMatchLocks", equalTo(1)))
            .andExpect(jsonPath("$.activeConnectionLocks", equalTo(1)))
            .andExpect(jsonPath("$.oldestQueueEntryEnteredAt").exists())
            .andExpect(jsonPath("$.oldestActiveLockCreatedAt").exists())
            .andExpect(jsonPath("$", not(hasKey("userId"))))
            .andExpect(jsonPath("$", not(hasKey("profileId"))))
            .andExpect(jsonPath("$", not(hasKey("matchId"))))
            .andExpect(jsonPath("$", not(hasKey("connectionId"))))
            .andExpect(jsonPath("$", not(hasKey("latitude"))))
            .andExpect(jsonPath("$", not(hasKey("longitude"))))
    }

    @Test
    fun `matchmaking diagnostics requires admin role`() {
        val user = userService.createUser("diagnostics-user-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/admin/diagnostics/matchmaking")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isForbidden)
    }
}
