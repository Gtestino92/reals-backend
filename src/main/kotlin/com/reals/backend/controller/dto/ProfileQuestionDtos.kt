package com.reals.backend.controller.dto

import com.reals.backend.service.profilequestion.ProfileQuestionAnswerView
import com.reals.backend.service.profilequestion.ProfileQuestionCatalog
import com.reals.backend.service.profilequestion.ProfileQuestionDefinition
import com.reals.backend.service.profilequestion.PublicProfileQuestionAnswer
import com.reals.backend.validation.SingleLinePlainText
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class ProfileQuestionCatalogResponse(
    val catalogVersion: String,
    val questions: List<ProfileQuestionResponse>
) {
    companion object {
        fun from(catalog: ProfileQuestionCatalog): ProfileQuestionCatalogResponse =
            ProfileQuestionCatalogResponse(
                catalogVersion = catalog.catalogVersion,
                questions = catalog.activeQuestions
                    .sortedBy { it.displayOrder }
                    .map(ProfileQuestionResponse::from)
            )
    }
}

data class ProfileQuestionResponse(
    val id: String,
    val semanticVersion: Int,
    val contentVersion: Int,
    val prompt: String,
    val displayOrder: Int
) {
    companion object {
        fun from(question: ProfileQuestionDefinition): ProfileQuestionResponse =
            ProfileQuestionResponse(
                id = question.id,
                semanticVersion = question.semanticVersion,
                contentVersion = question.contentVersion,
                prompt = question.prompt,
                displayOrder = question.displayOrder
            )
    }
}

data class ProfileQuestionAnswersResponse(
    val answers: List<ProfileQuestionAnswerResponse>
) {
    companion object {
        fun from(answers: List<ProfileQuestionAnswerView>): ProfileQuestionAnswersResponse =
            ProfileQuestionAnswersResponse(
                answers = answers.map(ProfileQuestionAnswerResponse::from)
            )
    }
}

data class ProfileQuestionAnswerResponse(
    val questionId: String,
    val questionSemanticVersion: Int,
    val answer: String,
    val selectedPosition: Int?,
    val current: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    companion object {
        fun from(view: ProfileQuestionAnswerView): ProfileQuestionAnswerResponse =
            ProfileQuestionAnswerResponse(
                questionId = view.answer.questionId,
                questionSemanticVersion = view.answer.questionSemanticVersion,
                answer = view.answer.answerText,
                selectedPosition = view.answer.selectedPosition,
                current = view.current,
                createdAt = view.answer.createdAt,
                updatedAt = view.answer.updatedAt
            )
    }
}

data class UpsertProfileQuestionAnswerRequest(
    @field:NotBlank
    @field:Size(max = 160)
    @field:Pattern(
        regexp = SingleLinePlainText.REGEX,
        message = SingleLinePlainText.MESSAGE
    )
    val answer: String
)

data class UpdateProfileQuestionSelectionsRequest(
    @field:Size(max = 3)
    val questionIds: List<String>
)

data class PublicProfileQuestionResponse(
    val questionId: String,
    val prompt: String,
    val answer: String,
    val position: Int
) {
    companion object {
        fun from(answer: PublicProfileQuestionAnswer): PublicProfileQuestionResponse =
            PublicProfileQuestionResponse(
                questionId = answer.questionId,
                prompt = answer.prompt,
                answer = answer.answer,
                position = answer.position
            )
    }
}
