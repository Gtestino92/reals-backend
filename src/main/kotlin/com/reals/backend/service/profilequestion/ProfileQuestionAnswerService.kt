package com.reals.backend.service.profilequestion

import com.reals.backend.domain.ProfileQuestionAnswer
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.repository.ProfileQuestionAnswerRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.validation.SingleLinePlainText
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

data class ProfileQuestionAnswerView(
    val answer: ProfileQuestionAnswer,
    val current: Boolean
)

data class PublicProfileQuestionAnswer(
    val questionId: String,
    val prompt: String,
    val answer: String,
    val position: Int
)

@Service
@Transactional
class ProfileQuestionAnswerService(
    private val answerRepository: ProfileQuestionAnswerRepository,
    private val profileRepository: ProfileRepository,
    private val catalogProvider: ProfileQuestionCatalogProvider
) {
    @Transactional(readOnly = true)
    fun getMyAnswers(userId: UUID): List<ProfileQuestionAnswerView> {
        val profile =
            profileRepository.findByUserId(userId)
                ?: throw DomainNotFoundException(
                    code = DomainErrorCode.PROFILE_NOT_FOUND,
                    message = "Profile not found for current user"
                )
        return answerViewsForProfile(profile.id)
    }

    fun upsertMyAnswer(
        userId: UUID,
        questionId: String,
        answerText: String
    ): List<ProfileQuestionAnswerView> {
        val profile = profileForCurrentUserForUpdateOrThrow(userId)
        requireAnswerableProfile(profile.status)
        val normalizedQuestionId = normalizeStableQuestionId(questionId, DomainErrorCode.INVALID_PROFILE_QUESTION)
        val normalizedAnswer = normalizeAnswer(answerText)
        val catalog = catalogProvider.getCatalog()
        val question =
            catalog.activeQuestionById(normalizedQuestionId)
                ?: throw DomainBadRequestException(
                    code = DomainErrorCode.INVALID_PROFILE_QUESTION,
                    message = "Profile question is missing, inactive, or unsupported"
                )
        val now = OffsetDateTime.now()
        val existing =
            answerRepository.findByProfileIdAndQuestionId(
                profileId = profile.id,
                questionId = question.id
            )

        val changed =
            if (existing == null) {
                answerRepository.save(
                    ProfileQuestionAnswer(
                        profileId = profile.id,
                        questionId = question.id,
                        questionSemanticVersion = question.semanticVersion,
                        answerText = normalizedAnswer,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                true
            } else if (
                existing.answerText != normalizedAnswer ||
                existing.questionSemanticVersion != question.semanticVersion
            ) {
                existing.answerText = normalizedAnswer
                existing.questionSemanticVersion = question.semanticVersion
                existing.updatedAt = now
                answerRepository.save(existing)
                true
            } else {
                false
            }

        if (changed) {
            profile.updatedAt = now
        }

        return answerViewsForProfile(profile.id)
    }

    fun deleteMyAnswer(
        userId: UUID,
        questionId: String
    ): List<ProfileQuestionAnswerView> {
        val profile = profileForCurrentUserForUpdateOrThrow(userId)
        requireAnswerableProfile(profile.status)
        val normalizedQuestionId = normalizeStableQuestionId(questionId, DomainErrorCode.INVALID_PROFILE_QUESTION)
        val existing =
            answerRepository.findByProfileIdAndQuestionId(
                profileId = profile.id,
                questionId = normalizedQuestionId
            )

        if (existing == null) {
            return answerViewsForProfile(profile.id)
        }

        val now = OffsetDateTime.now()
        answerRepository.delete(existing)
        answerRepository.flush()
        compactSelectedPositions(profile.id, now)
        profile.updatedAt = now

        return answerViewsForProfile(profile.id)
    }

    fun replaceMySelections(
        userId: UUID,
        orderedQuestionIds: List<String>
    ): List<ProfileQuestionAnswerView> {
        val profile = profileForCurrentUserForUpdateOrThrow(userId)
        requireAnswerableProfile(profile.status)
        val normalizedQuestionIds = normalizeSelectionRequest(orderedQuestionIds)
        val catalog = catalogProvider.getCatalog()
        val answers = answerRepository.findByProfileId(profile.id)
        val answersByQuestionId = answers.associateBy { it.questionId }

        normalizedQuestionIds.forEach { questionId ->
            val answer =
                answersByQuestionId[questionId]
                    ?: throw DomainBadRequestException(
                        code = DomainErrorCode.INVALID_PROFILE_QUESTION_SELECTION,
                        message = "Profile question selection requires an existing current answer"
                    )
            if (!isCurrent(answer, catalog)) {
                throw DomainBadRequestException(
                    code = DomainErrorCode.INVALID_PROFILE_QUESTION_SELECTION,
                    message = "Profile question selection requires an existing current answer"
                )
            }
        }

        val currentSelection =
            answers.filter { it.selectedPosition != null }
                .sortedBy { it.selectedPosition }
                .map { it.questionId }
        if (currentSelection == normalizedQuestionIds) {
            return answerViewsForProfile(profile.id)
        }

        val now = OffsetDateTime.now()
        answers.filter { it.selectedPosition != null }
            .forEach {
                it.selectedPosition = null
                it.updatedAt = now
            }
        answerRepository.saveAll(answers)
        answerRepository.flush()

        normalizedQuestionIds.forEachIndexed { index, questionId ->
            val answer = answersByQuestionId.getValue(questionId)
            answer.selectedPosition = index + 1
            answer.updatedAt = now
        }
        answerRepository.saveAll(normalizedQuestionIds.map { answersByQuestionId.getValue(it) })
        profile.updatedAt = now

        return answerViewsForProfile(profile.id)
    }

    @Transactional(readOnly = true)
    fun getPublicSelectedAnswers(profileId: UUID): List<PublicProfileQuestionAnswer> {
        val catalog = catalogProvider.getCatalog()
        return answerRepository.findByProfileIdAndSelectedPositionIsNotNull(profileId)
            .sortedBy { it.selectedPosition ?: Int.MAX_VALUE }
            .filter { isCurrent(it, catalog) }
            .take(MAX_PUBLIC_SELECTIONS)
            .mapIndexed { index, answer ->
                val question = catalog.activeQuestionById(answer.questionId)
                    ?: error("Current profile question answer lost active catalog definition")
                PublicProfileQuestionAnswer(
                    questionId = question.id,
                    prompt = question.prompt,
                    answer = answer.answerText,
                    position = index + 1
                )
            }
    }

    private fun answerViewsForProfile(profileId: UUID): List<ProfileQuestionAnswerView> {
        val catalog = catalogProvider.getCatalog()
        return answerRepository.findByProfileId(profileId)
            .sortedWith(
                compareBy<ProfileQuestionAnswer> {
                    catalog.displayOrderOf(it.questionId) ?: Int.MAX_VALUE
                }.thenBy {
                    it.questionId
                }
            )
            .map { ProfileQuestionAnswerView(answer = it, current = isCurrent(it, catalog)) }
    }

    private fun compactSelectedPositions(
        profileId: UUID,
        now: OffsetDateTime
    ) {
        val selected =
            answerRepository.findByProfileIdAndSelectedPositionIsNotNull(profileId)
                .sortedBy { it.selectedPosition ?: Int.MAX_VALUE }
        selected.forEachIndexed { index, answer ->
            val compactedPosition = index + 1
            if (answer.selectedPosition != compactedPosition) {
                answer.selectedPosition = compactedPosition
                answer.updatedAt = now
            }
        }
        answerRepository.saveAll(selected)
    }

    private fun isCurrent(
        answer: ProfileQuestionAnswer,
        catalog: ProfileQuestionCatalog
    ): Boolean {
        val question = catalog.activeQuestionById(answer.questionId) ?: return false
        return answer.questionSemanticVersion == question.semanticVersion
    }

    private fun profileForCurrentUserForUpdateOrThrow(userId: UUID) =
        profileRepository.findByUserIdForUpdate(userId)
            ?: throw DomainNotFoundException(
                code = DomainErrorCode.PROFILE_NOT_FOUND,
                message = "Profile not found for current user"
            )

    private fun requireAnswerableProfile(status: ProfileStatus) {
        if (status !in setOf(ProfileStatus.DRAFT, ProfileStatus.ACTIVE)) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_NOT_ACTIVE,
                message = "Profile question answers require a draft or active profile"
            )
        }
    }

    private fun normalizeSelectionRequest(questionIds: List<String>): List<String> {
        if (questionIds.size > MAX_PUBLIC_SELECTIONS) {
            throw DomainBadRequestException(
                code = DomainErrorCode.PROFILE_QUESTION_SELECTION_LIMIT_EXCEEDED,
                message = "At most $MAX_PUBLIC_SELECTIONS profile questions can be selected"
            )
        }
        val normalized =
            questionIds.map {
                normalizeStableQuestionId(it, DomainErrorCode.INVALID_PROFILE_QUESTION_SELECTION)
            }
        val duplicates =
            normalized.groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (duplicates.isNotEmpty()) {
            throw DomainBadRequestException(
                code = DomainErrorCode.DUPLICATE_PROFILE_QUESTION_SELECTION,
                message = "Profile question selection contains duplicate question ids"
            )
        }
        return normalized
    }

    private fun normalizeStableQuestionId(
        value: String,
        code: DomainErrorCode
    ): String {
        val normalized = value.trim()
        if (
            normalized.isBlank() ||
            normalized.length > MAX_STABLE_CODE_LENGTH ||
            !STABLE_CODE_PATTERN.matches(normalized)
        ) {
            throw DomainBadRequestException(
                code = code,
                message = "Invalid profile question id"
            )
        }
        return normalized
    }

    private fun normalizeAnswer(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_QUESTION_ANSWER,
                message = "Profile question answer must not be blank"
            )
        }
        if (normalized.length > MAX_ANSWER_LENGTH) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_QUESTION_ANSWER,
                message = "Profile question answer must be at most $MAX_ANSWER_LENGTH characters"
            )
        }
        if (!SingleLinePlainText.REGEX.toRegex().matches(normalized)) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_QUESTION_ANSWER,
                message = SingleLinePlainText.MESSAGE
            )
        }
        return normalized
    }

    private companion object {
        const val MAX_PUBLIC_SELECTIONS = 3
        const val MAX_ANSWER_LENGTH = 160
        const val MAX_STABLE_CODE_LENGTH = 64
        val STABLE_CODE_PATTERN = Regex("^[A-Z0-9_]+$")
    }
}
