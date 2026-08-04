package com.reals.backend.service.affinity

import com.reals.backend.domain.AffinityQuestionAnswer
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.repository.AffinityQuestionAnswerRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

data class AffinityAnswerPatch(
    val questionId: String,
    val answerCode: String
)

@Service
@Transactional
class AffinityQuestionAnswerService(
    private val answerRepository: AffinityQuestionAnswerRepository,
    private val profileRepository: ProfileRepository,
    private val catalogProvider: AffinityQuestionCatalogProvider
) {
    @Transactional(readOnly = true)
    fun getMyAnswers(userId: UUID): List<AffinityQuestionAnswer> {
        val profile = profileForCurrentUserOrThrow(userId)
        return answersForProfile(profile.id)
    }

    fun patchMyAnswers(
        userId: UUID,
        patches: List<AffinityAnswerPatch>
    ): List<AffinityQuestionAnswer> {
        val profile = profileForCurrentUserOrThrow(userId)
        requireAnswerableProfile(profile.status)
        rejectDuplicateQuestionIds(patches)

        val catalog = catalogProvider.getCatalog()
        val now = OffsetDateTime.now()
        patches.forEach { patch ->
            val question = activeQuestionOrThrow(catalog, patch.questionId)
            val answerCode = patch.answerCode.trim()
            if (question.optionByCode(answerCode) == null) {
                throw DomainBadRequestException(
                    code = DomainErrorCode.INVALID_AFFINITY_ANSWER,
                    message = "Invalid affinity answer option ${patch.answerCode} for question ${patch.questionId}"
                )
            }

            val existing =
                answerRepository.findByProfileIdAndQuestionId(
                    profileId = profile.id,
                    questionId = question.id
                )

            if (existing == null) {
                answerRepository.save(
                    AffinityQuestionAnswer(
                        profileId = profile.id,
                        questionId = question.id,
                        questionSemanticVersion = question.semanticVersion,
                        answerCode = answerCode,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            } else if (
                existing.answerCode != answerCode ||
                existing.questionSemanticVersion != question.semanticVersion
            ) {
                existing.answerCode = answerCode
                existing.questionSemanticVersion = question.semanticVersion
                existing.updatedAt = now
                answerRepository.save(existing)
            }
        }

        return answersForProfile(profile.id)
    }

    fun deleteMyAnswer(
        userId: UUID,
        questionId: String
    ): List<AffinityQuestionAnswer> {
        val profile = profileForCurrentUserOrThrow(userId)
        requireAnswerableProfile(profile.status)
        activeQuestionOrThrow(catalogProvider.getCatalog(), questionId)

        answerRepository.deleteByProfileIdAndQuestionId(
            profileId = profile.id,
            questionId = questionId
        )

        return answersForProfile(profile.id)
    }

    private fun answersForProfile(profileId: UUID): List<AffinityQuestionAnswer> {
        val catalog = catalogProvider.getCatalog()
        val categoryOrder = catalog.categories.associate { it.id to it.displayOrder }
        val questionOrder =
            catalog.questions.withIndex()
                .associate { it.value.id to it.index }

        return answerRepository.findByProfileId(profileId)
            .sortedWith(
                compareBy<AffinityQuestionAnswer> {
                    categoryOrder[catalog.activeQuestionById(it.questionId)?.categoryId] ?: Int.MAX_VALUE
                }.thenBy {
                    questionOrder[it.questionId] ?: Int.MAX_VALUE
                }.thenBy {
                    it.questionId
                }
            )
    }

    private fun profileForCurrentUserOrThrow(userId: UUID) =
        profileRepository.findByUserId(userId)
            ?: throw DomainNotFoundException(
                code = DomainErrorCode.PROFILE_NOT_FOUND,
                message = "Profile not found for current user"
            )

    private fun requireAnswerableProfile(status: ProfileStatus) {
        if (status !in setOf(ProfileStatus.DRAFT, ProfileStatus.ACTIVE)) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_NOT_ACTIVE,
                message = "Affinity answers require a draft or active profile"
            )
        }
    }

    private fun rejectDuplicateQuestionIds(patches: List<AffinityAnswerPatch>) {
        val duplicateQuestionIds =
            patches.map { it.questionId.trim() }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys

        if (duplicateQuestionIds.isNotEmpty()) {
            throw DomainBadRequestException(
                code = DomainErrorCode.DUPLICATE_AFFINITY_QUESTION,
                message = "Affinity answer request contains duplicate question ids: ${duplicateQuestionIds.joinToString()}"
            )
        }
    }

    private fun activeQuestionOrThrow(
        catalog: AffinityQuestionCatalog,
        questionId: String
    ): AffinityQuestion =
        catalog.activeQuestionById(questionId.trim())
            ?: throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_AFFINITY_QUESTION,
                message = "Affinity question is missing, inactive, or unsupported: $questionId"
            )
}
