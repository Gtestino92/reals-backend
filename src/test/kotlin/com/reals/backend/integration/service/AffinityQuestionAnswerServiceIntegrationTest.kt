package com.reals.backend.integration.service

import com.reals.backend.domain.AffinityQuestionAnswer
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.repository.AffinityQuestionAnswerRepository
import com.reals.backend.service.affinity.AffinityAnswerPatch
import com.reals.backend.service.affinity.AffinityQuestionAnswerService
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDate
import java.util.UUID

class AffinityQuestionAnswerServiceIntegrationTest : BaseIT() {
    @Autowired
    private lateinit var affinityQuestionAnswerService: AffinityQuestionAnswerService

    @Autowired
    private lateinit var affinityQuestionAnswerRepository: AffinityQuestionAnswerRepository

    @Test
    fun `create answer`() {
        val userId = createDraftProfile()

        val answers =
            affinityQuestionAnswerService.patchMyAnswers(
                userId = userId,
                patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT"))
            )

        assertEquals(1, answers.size)
        assertEquals("CINEMA_IMPORTANCE_001", answers.single().questionId)
        assertEquals("VERY_IMPORTANT", answers.single().answerCode)
        assertEquals(1, answers.single().questionSemanticVersion)
    }

    @Test
    fun `update existing answer idempotently`() {
        val userId = createDraftProfile()

        val first =
            affinityQuestionAnswerService.patchMyAnswers(
                userId = userId,
                patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "IMPORTANT"))
            ).single()

        val same =
            affinityQuestionAnswerService.patchMyAnswers(
                userId = userId,
                patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "IMPORTANT"))
            ).single()

        assertEquals(first.id, same.id)
        assertEquals(first.updatedAt, same.updatedAt)

        val updated =
            affinityQuestionAnswerService.patchMyAnswers(
                userId = userId,
                patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT"))
            ).single()

        assertEquals(first.id, updated.id)
        assertEquals("VERY_IMPORTANT", updated.answerCode)
    }

    @Test
    fun `partial patch preserves omitted answers`() {
        val userId = createDraftProfile()
        affinityQuestionAnswerService.patchMyAnswers(
            userId = userId,
            patches = listOf(
                AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "IMPORTANT"),
                AffinityAnswerPatch("MUSIC_IMPORTANCE_001", "IMPORTANT")
            )
        )

        val answers =
            affinityQuestionAnswerService.patchMyAnswers(
                userId = userId,
                patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT"))
            )

        assertEquals(
            mapOf(
                "CINEMA_IMPORTANCE_001" to "VERY_IMPORTANT",
                "MUSIC_IMPORTANCE_001" to "IMPORTANT"
            ),
            answers.associate { it.questionId to it.answerCode }
        )
    }

    @Test
    fun `duplicate question ids rejected`() {
        val userId = createDraftProfile()

        val ex =
            assertThrows<DomainBadRequestException> {
                affinityQuestionAnswerService.patchMyAnswers(
                    userId = userId,
                    patches = listOf(
                        AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "IMPORTANT"),
                        AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT")
                    )
                )
            }

        assertEquals(DomainErrorCode.DUPLICATE_AFFINITY_QUESTION, ex.code)
    }

    @Test
    fun `invalid question rejected`() {
        val userId = createDraftProfile()

        val ex =
            assertThrows<DomainBadRequestException> {
                affinityQuestionAnswerService.patchMyAnswers(
                    userId = userId,
                    patches = listOf(AffinityAnswerPatch("MISSING", "YES"))
                )
            }

        assertEquals(DomainErrorCode.INVALID_AFFINITY_QUESTION, ex.code)
    }

    @Test
    fun `invalid option rejected`() {
        val userId = createDraftProfile()

        val ex =
            assertThrows<DomainBadRequestException> {
                affinityQuestionAnswerService.patchMyAnswers(
                    userId = userId,
                    patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "MISSING"))
                )
            }

        assertEquals(DomainErrorCode.INVALID_AFFINITY_ANSWER, ex.code)
    }

    @Test
    fun `delete owned answer`() {
        val userId = createDraftProfile()
        affinityQuestionAnswerService.patchMyAnswers(
            userId = userId,
            patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "IMPORTANT"))
        )

        val answers =
            affinityQuestionAnswerService.deleteMyAnswer(
                userId = userId,
                questionId = "CINEMA_IMPORTANCE_001"
            )

        assertTrue(answers.isEmpty())
    }

    @Test
    fun `cannot affect another profile answer`() {
        val ownerUserId = createDraftProfile("owner")
        val otherUserId = createDraftProfile("other")
        affinityQuestionAnswerService.patchMyAnswers(
            userId = otherUserId,
            patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "IMPORTANT"))
        )

        affinityQuestionAnswerService.patchMyAnswers(
            userId = ownerUserId,
            patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "VERY_IMPORTANT"))
        )
        affinityQuestionAnswerService.deleteMyAnswer(
            userId = ownerUserId,
            questionId = "CINEMA_IMPORTANCE_001"
        )

        assertEquals(
            "IMPORTANT",
            affinityQuestionAnswerService.getMyAnswers(otherUserId).single().answerCode
        )
    }

    @Test
    fun `draft and active profiles may answer`() {
        val draftUserId = createDraftProfile("draft")
        val activeUserId =
            createActiveProfile(
                email = "affinity-active-${UUID.randomUUID()}@example.com",
                displayName = "Affinity Active",
                gender = Gender.FEMALE,
                lookingForGenders = setOf(Gender.MALE)
            )

        affinityQuestionAnswerService.patchMyAnswers(
            userId = draftUserId,
            patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "IMPORTANT"))
        )
        affinityQuestionAnswerService.patchMyAnswers(
            userId = activeUserId,
            patches = listOf(AffinityAnswerPatch("CINEMA_IMPORTANCE_001", "IMPORTANT"))
        )

        assertEquals(1, affinityQuestionAnswerService.getMyAnswers(draftUserId).size)
        assertEquals(1, affinityQuestionAnswerService.getMyAnswers(activeUserId).size)
    }

    @Test
    fun `missing profile behavior`() {
        val ex =
            assertThrows<DomainNotFoundException> {
                affinityQuestionAnswerService.getMyAnswers(UUID.randomUUID())
            }

        assertEquals(DomainErrorCode.PROFILE_NOT_FOUND, ex.code)
    }

    @Test
    fun `uniqueness invariant`() {
        val userId = createDraftProfile()
        val profile = profileRepository.findByUserId(userId) ?: error("Profile missing")

        affinityQuestionAnswerRepository.saveAndFlush(
            AffinityQuestionAnswer(
                profileId = profile.id,
                questionId = "CINEMA_IMPORTANCE_001",
                questionSemanticVersion = 1,
                answerCode = "IMPORTANT"
            )
        )

        assertThrows<DataIntegrityViolationException> {
            affinityQuestionAnswerRepository.saveAndFlush(
                AffinityQuestionAnswer(
                    profileId = profile.id,
                    questionId = "CINEMA_IMPORTANCE_001",
                    questionSemanticVersion = 1,
                    answerCode = "VERY_IMPORTANT"
                )
            )
        }
    }

    private fun createDraftProfile(prefix: String = "affinity"): UUID {
        val user = userService.createUser("$prefix-${UUID.randomUUID()}@example.com")
        val profile =
            profileService.createProfile(
                userId = user.id,
                displayName = "Affinity Draft",
                birthDate = LocalDate.of(1995, 1, 1),
                gender = Gender.FEMALE,
                lookingForGenders = setOf(Gender.MALE),
                intention = Intention.DATE,
                city = "Buenos Aires",
                countryCode = "AR",
                bio = "Affinity test profile",
                preferredMinAge = 18,
                preferredMaxAge = 99,
                maxDistanceKm = 50
            )

        assertEquals(ProfileStatus.DRAFT, profile.status)
        return user.id
    }
}
