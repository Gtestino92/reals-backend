package com.reals.backend.service.profilequestion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertFailsWith

class ProfileQuestionCatalogTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `bundled resource loads with initial order`() {
        val catalog = baseCatalog()

        ProfileQuestionCatalogValidator.validate(catalog)

        assertEquals("2026-08-01", catalog.catalogVersion)
        assertEquals(18, catalog.activeQuestions.size)
        assertEquals(
            listOf(
                "PERFECT_SUNDAY_001",
                "TALK_FOR_HOURS_001",
                "SMALL_JOY_001",
                "IDEAL_FIRST_DATE_001",
                "RECENT_DISCOVERY_001",
                "UNEXPECTED_TALENT_001",
                "WIN_ME_OVER_001",
                "POSITIVE_SIGNAL_001",
                "LIFE_SOUNDTRACK_001",
                "NEVER_REFUSE_PLAN_001",
                "RETURN_PLACE_001",
                "LEARNING_NOW_001",
                "COMFORTABLE_SILENCE_001",
                "FRIENDS_SAY_001",
                "UNPOPULAR_OPINION_001",
                "ALWAYS_LAUGH_001",
                "RELATIONSHIP_VALUE_001",
                "CURRENT_OBSESSION_001"
            ),
            catalog.questions.sortedBy { it.displayOrder }.map { it.id }
        )
        assertEquals((1..18).toList(), catalog.questions.map { it.displayOrder })
        assertEquals(catalog.questions.map { it.id }.toSet().size, catalog.questions.size)
    }

    @Test
    fun `stable id length boundary is enforced`() {
        ProfileQuestionCatalogValidator.validate(
            ProfileQuestionCatalog(
                catalogVersion = "test",
                questions = listOf(baseQuestion(id = "A".repeat(64)))
            )
        )

        assertFailsWith<IllegalArgumentException> {
            ProfileQuestionCatalogValidator.validate(
                ProfileQuestionCatalog(
                    catalogVersion = "test",
                    questions = listOf(baseQuestion(id = "A".repeat(65)))
                )
            )
        }
    }

    @Test
    fun `malformed ids fail`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileQuestionCatalogValidator.validate(
                ProfileQuestionCatalog(
                    catalogVersion = "test",
                    questions = listOf(baseQuestion(id = "bad-id"))
                )
            )
        }
    }

    @Test
    fun `blank and multiline prompts fail`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileQuestionCatalogValidator.validate(
                ProfileQuestionCatalog("test", listOf(baseQuestion(prompt = " ")))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileQuestionCatalogValidator.validate(
                ProfileQuestionCatalog("test", listOf(baseQuestion(prompt = "Línea uno\nLínea dos")))
            )
        }
    }

    @Test
    fun `duplicate ids and display orders fail`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileQuestionCatalogValidator.validate(
                ProfileQuestionCatalog(
                    catalogVersion = "test",
                    questions = listOf(
                        baseQuestion(id = "QUESTION_001", displayOrder = 1),
                        baseQuestion(id = "QUESTION_001", displayOrder = 2)
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileQuestionCatalogValidator.validate(
                ProfileQuestionCatalog(
                    catalogVersion = "test",
                    questions = listOf(
                        baseQuestion(id = "QUESTION_001", displayOrder = 1),
                        baseQuestion(id = "QUESTION_002", displayOrder = 1)
                    )
                )
            )
        }
    }

    @Test
    fun `invalid semantic and content versions fail`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileQuestionCatalogValidator.validate(
                ProfileQuestionCatalog("test", listOf(baseQuestion(semanticVersion = 0)))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileQuestionCatalogValidator.validate(
                ProfileQuestionCatalog("test", listOf(baseQuestion(contentVersion = 0)))
            )
        }
    }

    @Test
    fun `inactive questions are excluded from active output`() {
        val catalog =
            ProfileQuestionCatalog(
                catalogVersion = "test",
                questions = listOf(
                    baseQuestion(id = "ACTIVE_001", displayOrder = 1, active = true),
                    baseQuestion(id = "INACTIVE_001", displayOrder = 2, active = false)
                )
            )

        ProfileQuestionCatalogValidator.validate(catalog)

        assertEquals(listOf("ACTIVE_001"), catalog.activeQuestions.map { it.id })
        assertFalse(catalog.activeQuestions.any { it.id == "INACTIVE_001" })
    }

    @Test
    fun `unsupported active field type fails during parse`() {
        val json = """
            {
              "catalogVersion": "test",
              "questions": [
                {
                  "id": "QUESTION_001",
                  "semanticVersion": 1,
                  "contentVersion": 1,
                  "prompt": "Pregunta",
                  "displayOrder": 1,
                  "active": "yes"
                }
              ]
            }
        """.trimIndent()

        assertFailsWith<Exception> {
            objectMapper.readValue(json.byteInputStream(Charsets.UTF_8), ProfileQuestionCatalog::class.java)
        }
    }

    private fun baseCatalog(): ProfileQuestionCatalog {
        val input =
            requireNotNull(
                javaClass.classLoader.getResourceAsStream("profile-questions.es-AR.json")
            )
        return input.use {
            objectMapper.readValue(it, ProfileQuestionCatalog::class.java)
        }
    }

    private fun baseQuestion(
        id: String = "QUESTION_001",
        semanticVersion: Int = 1,
        contentVersion: Int = 1,
        prompt: String = "Pregunta válida",
        displayOrder: Int = 1,
        active: Boolean = true
    ): ProfileQuestionDefinition =
        ProfileQuestionDefinition(
            id = id,
            semanticVersion = semanticVersion,
            contentVersion = contentVersion,
            prompt = prompt,
            displayOrder = displayOrder,
            active = active
        )
}
