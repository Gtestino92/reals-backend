package com.reals.backend.integration.service

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.ProfileQuestionAnswer
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDate
import java.util.UUID

class ProfileQuestionAnswerServiceIntegrationTest : BaseIT() {
    @Test
    fun `no profile behavior`() {
        val user = userService.createUser("profile-question-no-profile-${UUID.randomUUID()}@example.com")

        val ex =
            assertThrows<DomainNotFoundException> {
                baseProfileQuestionAnswerService.getMyAnswers(user.id)
            }

        assertEquals(DomainErrorCode.PROFILE_NOT_FOUND, ex.code)
    }

    @Test
    fun `draft and active profiles can answer without changing status`() {
        val draftUserId = createDraftProfile("profile-question-draft")
        val activeUserId = createActiveProfile(
            email = "profile-question-active-${UUID.randomUUID()}@example.com",
            displayName = "Profile Question Active",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        baseProfileQuestionAnswerService.upsertMyAnswer(
            userId = draftUserId,
            questionId = "PERFECT_SUNDAY_001",
            answerText = "Café y una caminata sin apuro."
        )
        baseProfileQuestionAnswerService.upsertMyAnswer(
            userId = activeUserId,
            questionId = "PERFECT_SUNDAY_001",
            answerText = "Música y lectura."
        )

        assertEquals(ProfileStatus.DRAFT, profileService.findByUserId(draftUserId)!!.status)
        assertEquals(ProfileStatus.ACTIVE, profileService.findByUserId(activeUserId)!!.status)
    }

    @Test
    fun `unsupported profile status is rejected`() {
        val userId = createDraftProfile("profile-question-inactive")
        val profile = profileService.findByUserId(userId)!!
        profile.status = ProfileStatus.INACTIVE
        profileRepository.saveAndFlush(profile)

        val ex =
            assertThrows<com.reals.backend.service.exception.DomainConflictException> {
                baseProfileQuestionAnswerService.upsertMyAnswer(
                    userId = userId,
                    questionId = "PERFECT_SUNDAY_001",
                    answerText = "Respuesta válida"
                )
            }

        assertEquals(DomainErrorCode.PROFILE_NOT_ACTIVE, ex.code)
    }

    @Test
    fun `create update idempotency and selected position preservation`() {
        val userId = createDraftProfile("profile-question-upsert")

        val created =
            baseProfileQuestionAnswerService.upsertMyAnswer(
                userId = userId,
                questionId = " PERFECT_SUNDAY_001 ",
                answerText = "  Café, piano y una caminata sin apuro.  "
            ).single()

        assertEquals("Café, piano y una caminata sin apuro.", created.answer.answerText)
        assertEquals(1, created.answer.questionSemanticVersion)
        assertTrue(created.current)

        val same =
            baseProfileQuestionAnswerService.upsertMyAnswer(
                userId = userId,
                questionId = "PERFECT_SUNDAY_001",
                answerText = "Café, piano y una caminata sin apuro."
            ).single()
        assertEquals(created.answer.id, same.answer.id)
        assertEquals(created.answer.updatedAt, same.answer.updatedAt)

        baseProfileQuestionAnswerService.replaceMySelections(userId, listOf("PERFECT_SUNDAY_001"))
        val updated =
            baseProfileQuestionAnswerService.upsertMyAnswer(
                userId = userId,
                questionId = "PERFECT_SUNDAY_001",
                answerText = "Música, café y una caminata sin apuro."
            ).single()

        assertEquals(1, updated.answer.selectedPosition)
    }

    @Test
    fun `more than three private answers are allowed but four selections are rejected`() {
        val userId = createDraftProfile("profile-question-private-many")
        val questionIds = listOf(
            "PERFECT_SUNDAY_001",
            "TALK_FOR_HOURS_001",
            "SMALL_JOY_001",
            "IDEAL_FIRST_DATE_001"
        )
        questionIds.forEachIndexed { index, questionId ->
            baseProfileQuestionAnswerService.upsertMyAnswer(userId, questionId, "Respuesta ${index + 1}")
        }

        assertEquals(4, baseProfileQuestionAnswerService.getMyAnswers(userId).size)

        val ex =
            assertThrows<DomainBadRequestException> {
                baseProfileQuestionAnswerService.replaceMySelections(userId, questionIds)
            }

        assertEquals(DomainErrorCode.PROFILE_QUESTION_SELECTION_LIMIT_EXCEEDED, ex.code)
    }

    @Test
    fun `answer validation boundaries`() {
        val userId = createDraftProfile("profile-question-validation")

        baseProfileQuestionAnswerService.upsertMyAnswer(
            userId = userId,
            questionId = "PERFECT_SUNDAY_001",
            answerText = "a".repeat(160)
        )

        listOf("", "   ", "línea uno\nlínea dos", "a".repeat(161)).forEach { invalidAnswer ->
            val ex =
                assertThrows<DomainBadRequestException> {
                    baseProfileQuestionAnswerService.upsertMyAnswer(
                        userId = userId,
                        questionId = "TALK_FOR_HOURS_001",
                        answerText = invalidAnswer
                    )
                }
            assertEquals(DomainErrorCode.INVALID_PROFILE_QUESTION_ANSWER, ex.code)
        }
    }

    @Test
    fun `unknown question and invalid stable ids are rejected`() {
        val userId = createDraftProfile("profile-question-invalid-question")

        val unknown =
            assertThrows<DomainBadRequestException> {
                baseProfileQuestionAnswerService.upsertMyAnswer(userId, "MISSING_001", "Respuesta válida")
            }
        val malformed =
            assertThrows<DomainBadRequestException> {
                baseProfileQuestionAnswerService.replaceMySelections(userId, listOf("bad-id"))
            }

        assertEquals(DomainErrorCode.INVALID_PROFILE_QUESTION, unknown.code)
        assertEquals(DomainErrorCode.INVALID_PROFILE_QUESTION_SELECTION, malformed.code)
    }

    @Test
    fun `stale semantic answers are private current false and cannot be selected`() {
        val userId = createDraftProfile("profile-question-stale")
        val profile = profileService.findByUserId(userId)!!
        profileQuestionAnswerRepository.saveAndFlush(
            ProfileQuestionAnswer(
                profileId = profile.id,
                questionId = "PERFECT_SUNDAY_001",
                questionSemanticVersion = 2,
                answerText = "Respuesta vieja"
            )
        )

        val privateAnswer = baseProfileQuestionAnswerService.getMyAnswers(userId).single()
        assertEquals(false, privateAnswer.current)

        val ex =
            assertThrows<DomainBadRequestException> {
                baseProfileQuestionAnswerService.replaceMySelections(userId, listOf("PERFECT_SUNDAY_001"))
            }
        assertEquals(DomainErrorCode.INVALID_PROFILE_QUESTION_SELECTION, ex.code)

        val refreshed =
            baseProfileQuestionAnswerService.upsertMyAnswer(
                userId = userId,
                questionId = "PERFECT_SUNDAY_001",
                answerText = "Respuesta nueva"
            ).single()
        assertEquals(1, refreshed.answer.questionSemanticVersion)
        assertEquals(true, refreshed.current)
    }

    @Test
    fun `selection replacement order clearing idempotency and failed atomicity`() {
        val userId = createDraftProfile("profile-question-selection")
        listOf("PERFECT_SUNDAY_001", "LIFE_SOUNDTRACK_001", "CURRENT_OBSESSION_001").forEachIndexed { index, questionId ->
            baseProfileQuestionAnswerService.upsertMyAnswer(userId, questionId, "Respuesta ${index + 1}")
        }

        val selected =
            baseProfileQuestionAnswerService.replaceMySelections(
                userId,
                listOf("CURRENT_OBSESSION_001", "PERFECT_SUNDAY_001")
            )
        assertEquals(
            mapOf("CURRENT_OBSESSION_001" to 1, "PERFECT_SUNDAY_001" to 2, "LIFE_SOUNDTRACK_001" to null),
            selected.associate { it.answer.questionId to it.answer.selectedPosition }
        )

        val repeated =
            baseProfileQuestionAnswerService.replaceMySelections(
                userId,
                listOf("CURRENT_OBSESSION_001", "PERFECT_SUNDAY_001")
            )
        assertEquals(selected.map { it.answer.updatedAt }, repeated.map { it.answer.updatedAt })

        val duplicate =
            assertThrows<DomainBadRequestException> {
                baseProfileQuestionAnswerService.replaceMySelections(
                    userId,
                    listOf("PERFECT_SUNDAY_001", "PERFECT_SUNDAY_001")
                )
            }
        assertEquals(DomainErrorCode.DUPLICATE_PROFILE_QUESTION_SELECTION, duplicate.code)

        val unanswered =
            assertThrows<DomainBadRequestException> {
                baseProfileQuestionAnswerService.replaceMySelections(userId, listOf("TALK_FOR_HOURS_001"))
            }
        assertEquals(DomainErrorCode.INVALID_PROFILE_QUESTION_SELECTION, unanswered.code)
        assertEquals(
            listOf("CURRENT_OBSESSION_001" to 1, "PERFECT_SUNDAY_001" to 2),
            baseProfileQuestionAnswerService.getMyAnswers(userId)
                .filter { it.answer.selectedPosition != null }
                .sortedBy { it.answer.selectedPosition }
                .map { it.answer.questionId to it.answer.selectedPosition }
        )

        val cleared = baseProfileQuestionAnswerService.replaceMySelections(userId, emptyList())
        assertEquals(3, cleared.size)
        assertTrue(cleared.all { it.answer.selectedPosition == null })
    }

    @Test
    fun `delete selected answer compacts positions and missing delete is idempotent`() {
        val userId = createDraftProfile("profile-question-delete")
        listOf("PERFECT_SUNDAY_001", "LIFE_SOUNDTRACK_001", "CURRENT_OBSESSION_001").forEachIndexed { index, questionId ->
            baseProfileQuestionAnswerService.upsertMyAnswer(userId, questionId, "Respuesta ${index + 1}")
        }
        baseProfileQuestionAnswerService.replaceMySelections(
            userId,
            listOf("PERFECT_SUNDAY_001", "LIFE_SOUNDTRACK_001", "CURRENT_OBSESSION_001")
        )

        val remaining = baseProfileQuestionAnswerService.deleteMyAnswer(userId, "LIFE_SOUNDTRACK_001")

        assertEquals(
            mapOf("PERFECT_SUNDAY_001" to 1, "CURRENT_OBSESSION_001" to 2),
            remaining.associate { it.answer.questionId to it.answer.selectedPosition }
        )

        val same = baseProfileQuestionAnswerService.deleteMyAnswer(userId, "LIFE_SOUNDTRACK_001")
        assertEquals(2, same.size)
    }

    @Test
    fun `active profile remains active after all mutation types`() {
        val userId = createActiveProfile(
            email = "profile-question-active-invariant-${UUID.randomUUID()}@example.com",
            displayName = "Active Invariant",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        baseProfileQuestionAnswerService.upsertMyAnswer(userId, "PERFECT_SUNDAY_001", "Respuesta inicial")
        assertEquals(ProfileStatus.ACTIVE, profileService.findByUserId(userId)!!.status)
        baseProfileQuestionAnswerService.upsertMyAnswer(userId, "PERFECT_SUNDAY_001", "Respuesta editada")
        assertEquals(ProfileStatus.ACTIVE, profileService.findByUserId(userId)!!.status)
        baseProfileQuestionAnswerService.replaceMySelections(userId, listOf("PERFECT_SUNDAY_001"))
        assertEquals(ProfileStatus.ACTIVE, profileService.findByUserId(userId)!!.status)
        baseProfileQuestionAnswerService.deleteMyAnswer(userId, "PERFECT_SUNDAY_001")
        assertEquals(ProfileStatus.ACTIVE, profileService.findByUserId(userId)!!.status)
    }

    @Test
    fun `repository constraints enforce uniqueness and nullable selections`() {
        val userA = createDraftProfile("profile-question-repository-a")
        val userB = createDraftProfile("profile-question-repository-b")
        val profileA = profileService.findByUserId(userA)!!
        val profileB = profileService.findByUserId(userB)!!

        profileQuestionAnswerRepository.saveAndFlush(answer(profileA.id, "PERFECT_SUNDAY_001", selectedPosition = 1))
        profileQuestionAnswerRepository.saveAndFlush(answer(profileA.id, "TALK_FOR_HOURS_001"))
        profileQuestionAnswerRepository.saveAndFlush(answer(profileA.id, "SMALL_JOY_001"))
        profileQuestionAnswerRepository.saveAndFlush(answer(profileB.id, "PERFECT_SUNDAY_001", selectedPosition = 1))

        assertEquals(3, profileQuestionAnswerRepository.findByProfileId(profileA.id).size)

        assertThrows<DataIntegrityViolationException> {
            profileQuestionAnswerRepository.saveAndFlush(answer(profileA.id, "PERFECT_SUNDAY_001"))
        }
    }

    private fun createDraftProfile(prefix: String): UUID {
        val user = userService.createUser("$prefix-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Profile Questions",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            bio = "Perfil de prueba",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )
        return user.id
    }

    private fun answer(
        profileId: UUID,
        questionId: String,
        selectedPosition: Int? = null
    ): ProfileQuestionAnswer =
        ProfileQuestionAnswer(
            profileId = profileId,
            questionId = questionId,
            questionSemanticVersion = 1,
            answerText = "Respuesta válida",
            selectedPosition = selectedPosition
        )
}
