package com.reals.backend.service.profilequestion

import com.reals.backend.validation.SingleLinePlainText
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class ProfileQuestionCatalog(
    val catalogVersion: String,
    val questions: List<ProfileQuestionDefinition>
) {
    val activeQuestions: List<ProfileQuestionDefinition>
        get() = questions.filter { it.active }

    fun questionById(id: String): ProfileQuestionDefinition? =
        questions.firstOrNull { it.id == id }

    fun activeQuestionById(id: String): ProfileQuestionDefinition? =
        activeQuestions.firstOrNull { it.id == id }

    fun displayOrderOf(id: String): Int? =
        questionById(id)?.displayOrder
}

data class ProfileQuestionDefinition(
    val id: String,
    val semanticVersion: Int,
    val contentVersion: Int,
    val prompt: String,
    val displayOrder: Int,
    val active: Boolean
)

@Component
class ProfileQuestionCatalogProvider(
    private val objectMapper: ObjectMapper,
    private val resourceLoader: ResourceLoader,
    @param:Value("\${profile.questions.catalog:classpath:profile-questions.es-AR.json}")
    private val catalogLocation: String
) {
    private val catalog: ProfileQuestionCatalog = loadCatalog()

    fun getCatalog(): ProfileQuestionCatalog = catalog

    private fun loadCatalog(): ProfileQuestionCatalog {
        val resource = resourceLoader.getResource(catalogLocation)
        val loaded =
            resource.inputStream.use { input ->
                objectMapper.readValue(input, ProfileQuestionCatalog::class.java)
            }

        ProfileQuestionCatalogValidator.validate(loaded)
        return loaded.copy(questions = loaded.questions.sortedBy { it.displayOrder })
    }
}

object ProfileQuestionCatalogValidator {
    private const val MAX_ID_LENGTH = 64
    private const val MAX_PROMPT_LENGTH = 180
    private val STABLE_ID_PATTERN = Regex("^[A-Z0-9_]+$")

    fun validate(catalog: ProfileQuestionCatalog) {
        require(catalog.catalogVersion.isNotBlank()) {
            "Profile question catalog version must not be blank"
        }
        require(catalog.questions.isNotEmpty()) {
            "Profile question catalog must contain at least one question"
        }

        requireUnique("question ids", catalog.questions.map { it.id })
        requireUnique("question display orders", catalog.questions.map { it.displayOrder.toString() })
        catalog.questions.forEach { validateQuestion(it) }
    }

    private fun validateQuestion(question: ProfileQuestionDefinition) {
        require(question.id.isNotBlank()) {
            "Profile question catalog contains a blank question id"
        }
        require(question.id.length <= MAX_ID_LENGTH) {
            "Profile question ${question.id} id exceeds $MAX_ID_LENGTH characters"
        }
        require(STABLE_ID_PATTERN.matches(question.id)) {
            "Profile question ${question.id} id must match ${STABLE_ID_PATTERN.pattern}"
        }
        require(question.semanticVersion >= 1) {
            "Profile question ${question.id} semanticVersion must be positive"
        }
        require(question.contentVersion >= 1) {
            "Profile question ${question.id} contentVersion must be positive"
        }
        require(question.displayOrder > 0) {
            "Profile question ${question.id} displayOrder must be positive"
        }
        require(question.prompt.isNotBlank()) {
            "Profile question ${question.id} prompt must not be blank"
        }
        require(question.prompt.length <= MAX_PROMPT_LENGTH) {
            "Profile question ${question.id} prompt exceeds $MAX_PROMPT_LENGTH characters"
        }
        SingleLinePlainText.requireValid("profile question prompt", question.prompt)
    }

    private fun requireUnique(
        label: String,
        values: List<String>
    ) {
        val duplicates =
            values.groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(duplicates.isEmpty()) {
            "Profile question catalog contains duplicate $label: ${duplicates.joinToString()}"
        }
    }
}
