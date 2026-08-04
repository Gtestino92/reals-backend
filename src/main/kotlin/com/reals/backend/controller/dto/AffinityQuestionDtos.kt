package com.reals.backend.controller.dto

import com.reals.backend.domain.AffinityQuestionAnswer
import com.reals.backend.service.affinity.AffinityAnswerType
import com.reals.backend.service.affinity.AffinityQuestion
import com.reals.backend.service.affinity.AffinityQuestionCatalog
import com.reals.backend.service.affinity.AffinityQuestionCategory
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class AffinityQuestionCatalogResponse(
    val catalogVersion: String,
    val categories: List<AffinityQuestionCategoryResponse>,
    val questions: List<AffinityQuestionResponse>
) {
    companion object {
        fun from(catalog: AffinityQuestionCatalog): AffinityQuestionCatalogResponse =
            AffinityQuestionCatalogResponse(
                catalogVersion = catalog.catalogVersion,
                categories = catalog.categories
                    .sortedBy { it.displayOrder }
                    .map(AffinityQuestionCategoryResponse::from),
                questions = catalog.activeQuestions.withIndex()
                    .sortedWith(
                        compareBy<IndexedValue<AffinityQuestion>> {
                            catalog.categoryById(it.value.categoryId)?.displayOrder ?: Int.MAX_VALUE
                        }.thenBy {
                            it.index
                        }.thenBy {
                            it.value.id
                        }
                    )
                    .map { AffinityQuestionResponse.from(it.value) }
            )
    }
}

data class AffinityQuestionCategoryResponse(
    val id: String,
    val title: String,
    val description: String?,
    val displayOrder: Int
) {
    companion object {
        fun from(category: AffinityQuestionCategory): AffinityQuestionCategoryResponse =
            AffinityQuestionCategoryResponse(
                id = category.id,
                title = category.title,
                description = category.description,
                displayOrder = category.displayOrder
            )
    }
}

data class AffinityQuestionResponse(
    val id: String,
    val semanticVersion: Int,
    val contentVersion: Int,
    val categoryId: String,
    val primaryTopic: String,
    val topicTags: List<String>,
    val answerType: AffinityAnswerType,
    val prompt: String,
    val options: List<AffinityAnswerOptionResponse>
) {
    companion object {
        fun from(question: AffinityQuestion): AffinityQuestionResponse =
            AffinityQuestionResponse(
                id = question.id,
                semanticVersion = question.semanticVersion,
                contentVersion = question.contentVersion,
                categoryId = question.categoryId,
                primaryTopic = question.primaryTopic,
                topicTags = question.topicTags,
                answerType = question.answerType,
                prompt = question.prompt,
                options = question.options
                    .sortedBy { it.displayOrder }
                    .map {
                        AffinityAnswerOptionResponse(
                            code = it.code,
                            label = it.label,
                            displayOrder = it.displayOrder
                        )
                    }
            )
    }
}

data class AffinityAnswerOptionResponse(
    val code: String,
    val label: String,
    val displayOrder: Int
)

data class PatchAffinityAnswersRequest(
    @field:Valid
    @field:Size(max = 100)
    val answers: List<PatchAffinityAnswerRequest>
)

data class PatchAffinityAnswerRequest(
    @field:NotBlank
    @field:Size(max = 96)
    val questionId: String,

    @field:NotBlank
    @field:Size(max = 96)
    val answerCode: String
)

data class AffinityAnswersResponse(
    val answers: List<AffinityAnswerResponse>
) {
    companion object {
        fun from(answers: List<AffinityQuestionAnswer>): AffinityAnswersResponse =
            AffinityAnswersResponse(
                answers = answers.map(AffinityAnswerResponse::from)
            )
    }
}

data class AffinityAnswerResponse(
    val questionId: String,
    val questionSemanticVersion: Int,
    val answerCode: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    companion object {
        fun from(answer: AffinityQuestionAnswer): AffinityAnswerResponse =
            AffinityAnswerResponse(
                questionId = answer.questionId,
                questionSemanticVersion = answer.questionSemanticVersion,
                answerCode = answer.answerCode,
                createdAt = answer.createdAt,
                updatedAt = answer.updatedAt
            )
    }
}
