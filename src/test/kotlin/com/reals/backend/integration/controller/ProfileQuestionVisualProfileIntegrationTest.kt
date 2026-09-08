package com.reals.backend.integration.controller

import com.reals.backend.domain.ProfileQuestionAnswer
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

class ProfileQuestionVisualProfileIntegrationTest : ControllerIT() {
    @Test
    fun `no selected answers returns empty profileQuestions`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profileQuestions.length()", equalTo(0)))
    }

    @Test
    fun `one to three selected answers are exposed in exact selected order with public shape only`() {
        val setup = createMatchInVisualPhase()
        baseProfileQuestionAnswerService.upsertMyAnswer(setup.userBId, "PERFECT_SUNDAY_001", "Café y caminata.")
        baseProfileQuestionAnswerService.upsertMyAnswer(setup.userBId, "LIFE_SOUNDTRACK_001", "Música")
        baseProfileQuestionAnswerService.upsertMyAnswer(setup.userBId, "CURRENT_OBSESSION_001", "Leer ensayos.")
        baseProfileQuestionAnswerService.upsertMyAnswer(setup.userBId, "TALK_FOR_HOURS_001", "Cine y viajes.")
        baseProfileQuestionAnswerService.replaceMySelections(
            setup.userBId,
            listOf("CURRENT_OBSESSION_001", "PERFECT_SUNDAY_001", "LIFE_SOUNDTRACK_001")
        )

        val result =
            mockMvc.perform(
                get("/api/matches/${setup.matchId}/visual-profile")
                    .with(authenticatedAs(setup.userAId))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.profileQuestions.length()", equalTo(3)))
                .andExpect(jsonPath("$.profileQuestions[0].questionId", equalTo("CURRENT_OBSESSION_001")))
                .andExpect(jsonPath("$.profileQuestions[0].prompt", equalTo("Mi obsesión actual es...")))
                .andExpect(jsonPath("$.profileQuestions[0].answer", equalTo("Leer ensayos.")))
                .andExpect(jsonPath("$.profileQuestions[0].position", equalTo(1)))
                .andExpect(jsonPath("$.profileQuestions[1].questionId", equalTo("PERFECT_SUNDAY_001")))
                .andExpect(jsonPath("$.profileQuestions[1].position", equalTo(2)))
                .andExpect(jsonPath("$.profileQuestions[2].questionId", equalTo("LIFE_SOUNDTRACK_001")))
                .andExpect(jsonPath("$.profileQuestions[2].answer", equalTo("Música")))
                .andExpect(jsonPath("$.profileQuestions[2].position", equalTo(3)))
                .andExpect(content().string(not(containsString("Cine y viajes."))))
                .andExpect(content().string(not(containsString("questionSemanticVersion"))))
                .andExpect(content().string(not(containsString("selectedPosition"))))
                .andExpect(content().string(not(containsString("current"))))
                .andReturn()

        val firstItem = objectMapper.readTree(result.response.contentAsString).get("profileQuestions").first()
        assertEquals(4, firstItem.size())
    }

    @Test
    fun `stale selected answers are absent and positions are compacted`() {
        val setup = createMatchInVisualPhase()
        val partnerProfile = profileService.findByUserId(setup.userBId)!!
        profileQuestionAnswerRepository.saveAndFlush(
            ProfileQuestionAnswer(
                profileId = partnerProfile.id,
                questionId = "PERFECT_SUNDAY_001",
                questionSemanticVersion = 2,
                answerText = "Respuesta vieja",
                selectedPosition = 1
            )
        )
        profileQuestionAnswerRepository.saveAndFlush(
            ProfileQuestionAnswer(
                profileId = partnerProfile.id,
                questionId = "LIFE_SOUNDTRACK_001",
                questionSemanticVersion = 1,
                answerText = "Música",
                selectedPosition = 2
            )
        )

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profileQuestions.length()", equalTo(1)))
            .andExpect(jsonPath("$.profileQuestions[0].questionId", equalTo("LIFE_SOUNDTRACK_001")))
            .andExpect(jsonPath("$.profileQuestions[0].position", equalTo(1)))
            .andExpect(content().string(not(containsString("Respuesta vieja"))))
    }

    @Test
    fun `unauthorized and pre visual access reveal nothing`() {
        val setup = createMatchWithFirstChat()
        val stranger = userService.createUser("profile-question-stranger-${java.util.UUID.randomUUID()}@example.com")
        baseProfileQuestionAnswerService.upsertMyAnswer(setup.userBId, "PERFECT_SUNDAY_001", "Respuesta visible")
        baseProfileQuestionAnswerService.replaceMySelections(setup.userBId, listOf("PERFECT_SUNDAY_001"))

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(content().string(not(containsString("Respuesta visible"))))

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(content().string(not(containsString("Respuesta visible"))))
    }

    @Test
    fun `first chat response does not include profile question content`() {
        val setup = createMatchWithFirstChat()
        baseProfileQuestionAnswerService.upsertMyAnswer(setup.userBId, "PERFECT_SUNDAY_001", "Respuesta privada")
        baseProfileQuestionAnswerService.replaceMySelections(setup.userBId, listOf("PERFECT_SUNDAY_001"))

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/chat")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Respuesta privada"))))
    }
}
